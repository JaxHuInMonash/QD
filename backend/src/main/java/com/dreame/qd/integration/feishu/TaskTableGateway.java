package com.dreame.qd.integration.feishu;

import com.dreame.qd.model.Models.TaskRow;
import java.util.List;

/** INT-06 主表格读写网关（03 2.1 的 13 个字段）。失败抛 QdException（QD-FEISHU-*）。 */
public interface TaskTableGateway {
    List<TaskRow> listAll();

    void createBatch(List<TaskRow> rows);

    /** rows 必须带 recordId。 */
    void updateBatch(List<TaskRow> rows);

    void deleteBatch(List<String> recordIds);
}
