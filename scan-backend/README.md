# scan-backend：扫码采集临时记录 + OMS 入库单推送

配合 `../index.html` 采集页使用：手机扫箱面 → 页面本地暂存编号 → 「统一上传」把整批记录存到本服务（TEMP 临时记录）→ 核对/补齐后推送到 OMS 创建入库单。

```
手机采集页 ──POST /api/records/batch──▶ scan-backend（H2 暂存 TEMP）
                                            │  POST /api/inbound/push
                                            │  ①组装 boxTypeList（相同箱型合并）
                                            │  ②HmacSHA256 加签（appSecret 只在服务端）
                                            ▼
                                    OMS  /openapi/v1/inboundOrder/create
```

## 技术栈与环境

- Spring Boot 2.7.18 + Java 8（本机 JDK1.8 直接可编译；服务器装 JRE 8+ 即可）
- H2 文件库（零运维，数据在运行目录 `data/`）；后续量大可换 MySQL
- 无其他中间件，零外部依赖（HTTP 用 JDK 自带 `HttpURLConnection`）

## 快速开始

```bash
cd scan-backend
mvn -q -DskipTests package        # 打 jar（target/scan-backend-0.1.0-SNAPSHOT.jar）
java -jar target/scan-backend-0.1.0-SNAPSHOT.jar    # 默认 8090 端口
# 或开发时：
mvn spring-boot:run
```

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查（免登录，部署自检用） |
| GET | `/api/login` | **独立登录页**（免登录打开；支持 `?next=` 指定登录后跳转页，默认草稿页） |
| POST | `/api/auth/login` | 登录 `{loginName,password}` → 返回令牌；之后所有请求带 `X-Token` 头 |
| POST | `/api/auth/logout` | 退出（令牌作废） |
| GET | `/api/session` | 当前登录账号信息 |
| POST | `/api/auth/password` | 修改自己的密码 `{oldPassword,newPassword}` |
| GET | `/api/accounts` | 账号维护表（仅管理员；密钥脱敏返回） |
| POST/PUT | `/api/accounts[/{id}]` | 新建/编辑账号（仅管理员；密码/appKey/appSecret 留空=不改） |
| POST | `/api/records/batch` | 页面「统一上传」入口，请求体与页面现有格式一致 `{count, records:[{no,sku,len,wid,hei,wgt,qty,units,ts}]}` |
| GET | `/api/records?status=TEMP` | 列出**当前账号**的记录（TEMP/PUSHED/ALL），默认 TEMP |
| PUT | `/api/records/{id}` | 编辑草稿记录（整单替换：sku/len/wid/hei/wgt/qty/units，null=清空；已推送的拒绝修改） |
| DELETE | `/api/records/{id}` | 删除一条（错扫的记录） |
| DELETE | `/api/records?status=TEMP` | 清空某状态 |
| POST | `/api/inbound/push?dryRun=true` | **只看不推**：返回组装好的 dataJson、signString、authcode，用于和 OMS 官方验签工具比对 |
| POST | `/api/inbound/push` | 正式推送：用**当前账号的** OMS 凭证创建入库单；成功则 TEMP→PUSHED 并回填单号 |
| GET | `/api/draft` | **草稿页**（免登录打开，页面内登录）：草稿列表/编辑/账号维护/推送（`https://scan.closevi.cn/api/draft`） |
| GET | `/api/config` | 配置摘要（当前账号视角，appSecret 绝不返回） |

## 登录与账号（数据隔离）

- 首次部署自动创建初始管理员 **admin**（**仓库不存放任何默认密码**）：不配置则启动时随机生成一个初始密码、**仅在启动日志打印一次**，登录后立即改；也可设环境变量 `ADMIN_INIT_PASSWORD`（或系统属性 `-Dadmin.init.password`）自定义该初始密码。`application.yml` 里原有的 appKey/appSecret/whCode 和历史记录都归入该账号。
- **采集页免密**：同事打开 `/` 只需「先选部门、再选账号」直接进入（部门名称在账号维护里自定义，如 百货/鞋子/服装1部/服装2部；未分组的账号归入「未分组」），切换账号走采集页弹窗里的「退出 / 切换账号」。业务账号建号时密码留空即可。
- **维护页门禁**：管理员账号不出现在选择列表里，只能通过选择页底部「管理员登录」输密码进入（接口层 `select` 也拒发管理员令牌）。
- 每个账号在「账号维护」里配置自己的 OMS appKey/appSecret/仓库代码；**账号之间的记录、推送互不相通**，推送用的是各自账号的密钥。
- 令牌默认 30 天有效、存库不因重启失效；改密码后旧令牌全部作废。

curl 示例：

```bash
# 模拟页面批量上传
curl -s -X POST http://127.0.0.1:8090/api/records/batch -H "Content-Type: application/json" -d '{
  "count":2,"records":[
    {"no":1,"sku":"SKU-A","len":"52","wid":"41","hei":"50","wgt":"18","qty":"12","ts":1696000000000},
    {"no":2,"sku":"SKU-B","len":"60","wid":"40","hei":"30","wgt":"12.5","qty":"3","ts":1696000001000}
  ]}'

# 组装预览（不发送）
curl -s -X POST "http://127.0.0.1:8090/api/inbound/push?dryRun=true"

# 正式推送（需先配好 appKey/appSecret/whCode）
curl -s -X POST http://127.0.0.1:8090/api/inbound/push
```

## OMS 配置（application.yml → oms.*）

| 配置 | 说明 |
|---|---|
| `base-url` | `https://api.xlwms.com` |
| `app-key` / `app-secret` | OMS 后台「API 信息」获取；**appSecret 只放服务端，绝不下发到页面** |
| `wh-code` | 目标仓库代码（待填） |
| `inbound-type` | `1` 一件代发（已确认） |
| `arrival-mode` | 到仓方式，默认 `2` 箱；1 快递包裹 / 3 托盘 / 4-9 货柜 / … 13 自发头程 |
| `is-submit` | `1` 创建后自动提交到 WMS |
| `box-mark-increment-type` | `2` 相同箱型合并 |
| `units-per-box` | **待确认**：每箱产品数量（OMS `productList[].quantity` 必填），骨架先按 1 |

## 加签实现与待验证点（重要）

官方文档：`https://apidoc-oms.xlwms.com/docs/开发验签工具`

实现（`oms/OmsSigner.java`）：
1. `data` 内字段按**字典序（不区分大小写）升序**递归排序，序列化为紧凑 JSON；
2. `appKey + dataJson + reqTime` 直接拼接（三者本身满足字典序，故顺序拼接即排序结果）；
3. `appSecret` 做 **HmacSHA256**，转 16 进制 = `authcode`，放 URL（`?authcode=`），其余参数 POST；
4. `reqTime` 为 10 位 UNIX 秒，**超 5 分钟会被 OMS 拒绝**（推送即时生成，无需处理）；
5. 发送 body 里的 `data` 子串与参与签名的 `dataJson` **逐字节一致**（同一份序列化结果）。

⚠️ **文档中给出的拼接样例与文字规则不一致**（样例把 `page` 排在 `outboundOrderNos` 前；按字典序应反过来）。本实现以文字规则为准。上线前必做一次比对：

```bash
curl -s -X POST "http://127.0.0.1:8090/api/inbound/push?dryRun=true"
# 把返回的 signString 贴到 OMS 后台「开发验签工具」里，看生成的 authcode 是否与返回的一致
```

若不一致，规则落点只有 `OmsSigner.canonicalDataJson` 一处（换比较器即可），改完跑 `mvn test`。

## 与采集页联调

页面里把 `index.html` 的 `OMS_URL` 从 `''` 改成后端地址：

```js
const OMS_URL='/api/records/batch';   // 生产：nginx 反代同源；本地调试可用 http://<本机IP>:8090/api/records/batch
```

页面行为不变：上传成功（HTTP 200）才清空本地暂存；失败保留在 localStorage 可重试。

## 服务器部署（下一步）

1. 服务器装 JRE 8+：`yum install -y java-1.8.0-openjdk-headless`（或 update-alternatives 指到已有 JDK）
2. 上传 jar + 配置：`/opt/scan-backend/scan-backend.jar`，`application.yml` 用 `--spring.config.additional-location=` 外挂
3. systemd 常驻：

```ini
# /etc/systemd/system/scan-backend.service
[Unit]
Description=scan-backend
After=network.target
[Service]
WorkingDirectory=/opt/scan-backend
ExecStart=/usr/bin/java -Xmx256m -jar /opt/scan-backend/scan-backend.jar --spring.config.additional-location=/opt/scan-backend/application.yml
Restart=always
[Install]
WantedBy=multi-user.target
```

4. nginx：在 scan-demo 的 443 server 里加反代（页面与接口同源，免 CORS）：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8090;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

## 目录结构

```
scan-backend/
├── pom.xml
├── src/main/java/com/scandemo/backend/
│   ├── ScanBackendApplication.java
│   ├── config/    OmsProperties、WebConfig(CORS)、GlobalExceptionHandler
│   ├── domain/    ScanRecord 实体、RecordStatus 枚举
│   ├── repo/      ScanRecordRepository
│   ├── oms/       OmsSigner(加签)、OmsClient(HTTP)
│   ├── service/   RecordService(暂存)、InboundService(组装+推送)
│   └── web/       RecordController、InboundController、dto/
├── src/main/resources/application.yml
└── src/test/java/...  OmsSignerTest(验签) + ScanFlowTest(全流程)
```

## 待确认清单

- [ ] `signString` 与官方验签工具比对（见上）
- [ ] `units-per-box` 每箱产品数量
- [ ] `wh-code` 目标仓库代码
- [ ] `thirdOrderNo` 命名规则（骨架为 `SCAN-yyyyMMddHHmmss`，若 OMS 侧要求唯一性更强再调整）
- [ ] OMS 超时/500 的自动重试策略（当前失败保持 TEMP，人工重推）
