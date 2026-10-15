package com.scandemo.backend.auth;

import com.scandemo.backend.config.OmsProperties;
import com.scandemo.backend.domain.Account;
import com.scandemo.backend.domain.AuthToken;
import com.scandemo.backend.repo.AccountRepository;
import com.scandemo.backend.repo.AuthTokenRepository;
import com.scandemo.backend.repo.ScanRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号与登录：维护表(account)的增改查、登录发令牌、令牌校验、密码修改。
 * 首次启动自动把 application.yml 里现有的 OMS 配置和已有记录归入初始管理员账号。
 */
@Service
public class AccountService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int DEFAULT_TOKEN_DAYS = 30;
    private static final int MAX_LOGIN_FAILS = 5;
    private static final long LOCK_MS = 60_000;

    private final AccountRepository accountRepo;
    private final AuthTokenRepository tokenRepo;
    private final ScanRecordRepository recordRepo;
    private final OmsProperties props;
    private final Environment env;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    /** 登录失败计数：loginName -> [失败数, 锁定截止毫秒] */
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();

    public AccountService(AccountRepository accountRepo, AuthTokenRepository tokenRepo,
                          ScanRecordRepository recordRepo, OmsProperties props, Environment env) {
        this.accountRepo = accountRepo;
        this.tokenRepo = tokenRepo;
        this.recordRepo = recordRepo;
        this.props = props;
        this.env = env;
    }

    /** 首次启动种子：建初始管理员，并把历史记录归到它名下。
     * 初始密码不写死：优先取配置项 admin.init.password / 环境变量 ADMIN_INIT_PASSWORD；
     * 都没配则随机生成一个，只在启动日志里打印一次，登录后必须立即修改。 */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accountRepo.count() > 0) {
            return;
        }
        String fromConfig = initPasswordFromConfig();
        boolean generated = fromConfig == null;
        String pwd = generated ? randomPassword() : fromConfig;
        Account admin = new Account();
        admin.setLoginName("admin");
        admin.setPasswordHash(encoder.encode(pwd));
        admin.setDisplayName("管理员");
        admin.setAppKey(props.getAppKey());
        admin.setAppSecret(props.getAppSecret());
        admin.setWhCode(props.getWhCode());
        admin.setAdmin(true);
        admin.setEnabled(true);
        admin = accountRepo.save(admin);
        int moved = recordRepo.assignAccountToOrphans(admin.getId());
        if (generated) {
            log.warn("已创建初始管理员 admin，随机生成初始密码：{}（仅本次日志显示一次，请立即登录修改），历史记录归入该账号 {} 条", pwd, moved);
        } else {
            log.warn("已创建初始管理员 admin，初始密码取自配置项 admin.init.password / 环境变量 ADMIN_INIT_PASSWORD（登录后请立即修改），历史记录归入该账号 {} 条", moved);
        }
    }

    /** 读取显式配置的初始密码（配置项 admin.init.password 或环境变量 ADMIN_INIT_PASSWORD）；未配置或为空返回 null（触发随机生成） */
    private String initPasswordFromConfig() {
        String p = env.getProperty("admin.init.password");
        if (p == null || p.trim().isEmpty()) {
            p = env.getProperty("ADMIN_INIT_PASSWORD");
        }
        return (p == null || p.trim().isEmpty()) ? null : p.trim();
    }

    private String randomPassword() {
        byte[] buf = new byte[9]; // 12 位 base64url
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // ==================== 登录 ====================

    @Transactional
    public Map<String, Object> login(String loginName, String password) {
        String name = loginName == null ? "" : loginName.trim();
        checkLock(name);
        Account acc = accountRepo.findByLoginName(name).orElse(null);
        if (acc == null || !encoder.matches(password == null ? "" : password, acc.getPasswordHash())) {
            fail(name);
            throw new IllegalArgumentException("账号或密码不对");
        }
        if (!acc.isEnabled()) {
            throw new IllegalArgumentException("该账号已停用，请联系管理员");
        }
        fails.remove(name);
        tokenRepo.deleteByExpiresAtBefore(LocalDateTime.now());
        AuthToken t = new AuthToken();
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        t.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(buf));
        t.setAccountId(acc.getId());
        t.setExpiresAt(LocalDateTime.now().plusDays(DEFAULT_TOKEN_DAYS));
        tokenRepo.save(t);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("token", t.getToken());
        out.put("loginName", acc.getLoginName());
        out.put("displayName", acc.getDisplayName() == null ? acc.getLoginName() : acc.getDisplayName());
        out.put("isAdmin", acc.isAdmin());
        return out;
    }

    public Account validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        AuthToken t = tokenRepo.findById(token).orElse(null);
        if (t == null || t.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        Account acc = accountRepo.findById(t.getAccountId()).orElse(null);
        if (acc == null || !acc.isEnabled()) {
            return null;
        }
        return acc;
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isEmpty()) {
            tokenRepo.deleteById(token);
        }
    }

    @Transactional
    public void changePassword(Long accountId, String oldPassword, String newPassword) {
        Account acc = require(accountId);
        if (!encoder.matches(oldPassword == null ? "" : oldPassword, acc.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不对");
        }
        checkPassword(newPassword);
        acc.setPasswordHash(encoder.encode(newPassword));
        accountRepo.save(acc);
        // 改密后其它设备的旧令牌全部失效（当前会话也需重新登录）
        tokenRepo.deleteAll(tokenRepo.findByAccountId(accountId));
    }

    // ==================== 维护表（管理员） ====================

    public Account require(Long accountId) {
        return accountRepo.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
    }

    public java.util.List<Account> listAccounts() {
        return accountRepo.findAllByOrderByIdAsc();
    }

    public Account requireAdmin(Long accountId) {
        Account acc = require(accountId);
        if (!acc.isAdmin()) {
            throw new SecurityException("只有管理员能做账号维护");
        }
        return acc;
    }

    @Transactional
    public Account createAccount(String loginName, String password, String displayName,
                                 String appKey, String appSecret, String whCode,
                                 boolean isAdmin, boolean enabled) {
        String name = loginName == null ? "" : loginName.trim();
        if (name.isEmpty() || name.length() > 50) {
            throw new IllegalArgumentException("登录名必填（50 字符以内）");
        }
        if (accountRepo.findByLoginName(name).isPresent()) {
            throw new IllegalArgumentException("登录名已存在：" + name);
        }
        checkPassword(password);
        Account acc = new Account();
        acc.setLoginName(name);
        acc.setPasswordHash(encoder.encode(password));
        acc.setDisplayName(displayName);
        acc.setAppKey(trimToNull(appKey));
        acc.setAppSecret(trimToNull(appSecret));
        acc.setWhCode(trimToNull(whCode));
        acc.setAdmin(isAdmin);
        acc.setEnabled(enabled);
        return accountRepo.save(acc);
    }

    /** 编辑账号；password/appKey/appSecret 传空表示保持不变 */
    @Transactional
    public Account updateAccount(Long operatorId, Long id, String displayName, String appKey,
                                 String appSecret, String whCode, Boolean isAdmin, Boolean enabled,
                                 String newPassword) {
        Account acc = require(id);
        if (id.equals(operatorId)) {
            if (Boolean.FALSE.equals(enabled)) {
                throw new IllegalArgumentException("不能停用自己");
            }
            if (Boolean.FALSE.equals(isAdmin)) {
                throw new IllegalArgumentException("不能取消自己的管理员身份");
            }
        }
        if (displayName != null) {
            acc.setDisplayName(displayName);
        }
        if (appKey != null && !appKey.trim().isEmpty()) {
            acc.setAppKey(appKey.trim());
        }
        if (appSecret != null && !appSecret.trim().isEmpty()) {
            acc.setAppSecret(appSecret.trim());
        }
        if (whCode != null && !whCode.trim().isEmpty()) {
            acc.setWhCode(whCode.trim());
        }
        if (isAdmin != null) {
            acc.setAdmin(isAdmin);
        }
        if (enabled != null) {
            acc.setEnabled(enabled);
        }
        if (newPassword != null && !newPassword.isEmpty()) {
            checkPassword(newPassword);
            acc.setPasswordHash(encoder.encode(newPassword));
            tokenRepo.deleteAll(tokenRepo.findByAccountId(id));
        }
        return accountRepo.save(acc);
    }

    /** 密钥脱敏展示：只露前 6 位 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return secret.length() <= 6 ? "******" : secret.substring(0, 6) + "…";
    }

    // ==================== 内部 ====================

    private void checkPassword(String password) {
        if (password == null || password.trim().length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
    }

    private void checkLock(String name) {
        long[] f = fails.get(name);
        if (f != null && f[0] >= MAX_LOGIN_FAILS && System.currentTimeMillis() < f[1]) {
            throw new IllegalArgumentException("尝试次数过多，请 1 分钟后再试");
        }
    }

    private void fail(String name) {
        long[] f = fails.computeIfAbsent(name, k -> new long[2]);
        synchronized (f) {
            if (System.currentTimeMillis() > f[1]) {
                f[0] = 0;
            }
            f[0]++;
            if (f[0] >= MAX_LOGIN_FAILS) {
                f[1] = System.currentTimeMillis() + LOCK_MS;
            }
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
