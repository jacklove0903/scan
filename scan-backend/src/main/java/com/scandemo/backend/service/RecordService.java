package com.scandemo.backend.service;

import com.scandemo.backend.auth.CurrentAccount;
import com.scandemo.backend.domain.RecordStatus;
import com.scandemo.backend.domain.ScanRecord;
import com.scandemo.backend.repo.ScanRecordRepository;
import com.scandemo.backend.web.dto.BatchUploadRequest;
import com.scandemo.backend.web.dto.RecordDto;
import com.scandemo.backend.web.dto.RecordUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 采集记录的暂存/查询/删除——全部限定在当前登录账号内，账号之间互不可见 */
@Service
public class RecordService {

    private final ScanRecordRepository repo;

    public RecordService(ScanRecordRepository repo) {
        this.repo = repo;
    }

    /** 页面「上传」：整批入库为 TEMP，返回落库后的实体（含 id/batchNo） */
    @Transactional
    public List<ScanRecord> saveBatch(BatchUploadRequest req) {
        Long acc = CurrentAccount.get();
        String batchNo = "B" + LocalDateTime.now().toString().replaceAll("[-:T]", "").substring(0, 14)
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        List<ScanRecord> entities = new ArrayList<>();
        for (RecordDto d : req.getRecords()) {
            ScanRecord r = new ScanRecord();
            r.setAccountId(acc);
            r.setNo(d.getNo());
            r.setSku(d.getSku() == null ? null : d.getSku().trim());
            r.setLength(d.getLen());
            r.setWidth(d.getWid());
            r.setHeight(d.getHei());
            r.setWeight(d.getWgt());
            r.setQty(d.getQty());
            r.setUnitsPerBox(d.getUnits());
            r.setStatus(RecordStatus.TEMP);
            r.setBatchNo(batchNo);
            entities.add(r);
        }
        repo.saveAll(entities);
        return entities;
    }

    public List<ScanRecord> list(RecordStatus status) {
        Long acc = CurrentAccount.get();
        if (status == null) {
            return repo.findByAccountIdOrderByIdDesc(acc);
        }
        return repo.findByAccountIdAndStatusOrderByNoAscIdAsc(acc, status);
    }

    @Transactional
    public void delete(Long id) {
        ScanRecord r = repo.findByIdAndAccountId(id, CurrentAccount.get())
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + id));
        repo.delete(r);
    }

    /** 清空某状态的记录（status=null 表示全部），返回删除条数 */
    @Transactional
    public int clear(RecordStatus status) {
        Long acc = CurrentAccount.get();
        List<ScanRecord> list = status == null
                ? repo.findByAccountIdOrderByIdDesc(acc)
                : repo.findByAccountIdAndStatusOrderByNoAscIdAsc(acc, status);
        repo.deleteAll(list);
        return list.size();
    }

    public long count(RecordStatus status) {
        return repo.countByAccountIdAndStatus(CurrentAccount.get(), status);
    }

    /** 编辑草稿记录（整单替换）；已推送的记录不允许修改 */
    @Transactional
    public ScanRecord update(Long id, RecordUpdateRequest req) {
        ScanRecord r = repo.findByIdAndAccountId(id, CurrentAccount.get())
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + id));
        if (r.getStatus() != RecordStatus.TEMP) {
            throw new IllegalStateException("已推送的记录不能修改（OMS 单号 " + r.getOmsOrderNo() + "）");
        }
        if (req.getQty() != null && req.getQty() <= 0) {
            throw new IllegalArgumentException("箱子数量要大于 0");
        }
        if (req.getUnits() != null && req.getUnits() <= 0) {
            throw new IllegalArgumentException("单箱数量要大于 0");
        }
        String sku = req.getSku() == null ? null : req.getSku().trim();
        r.setSku(sku == null || sku.isEmpty() ? null : sku);
        r.setLength(req.getLen());
        r.setWidth(req.getWid());
        r.setHeight(req.getHei());
        r.setWeight(req.getWgt());
        r.setQty(req.getQty());
        r.setUnitsPerBox(req.getUnits());
        return repo.save(r);
    }
}
