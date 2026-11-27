package com.dreame.qd.model;

/** 错误码（04 文档 1.3，格式 QD-{分类}-{三位序号}）。新增错误码须同步更新 04 文档错误码表。 */
public enum ErrorCode {
    JIRA_AUTH("QD-JIRA-001", "Jira 认证失败（401）"),
    JIRA_FORBIDDEN("QD-JIRA-002", "Jira 拒绝访问（403，含验证码锁定）"),
    JIRA_RATE_LIMIT("QD-JIRA-003", "Jira 限流（429）"),
    JIRA_TIMEOUT("QD-JIRA-004", "Jira 超时或网络不可达"),
    JIRA_SERVER("QD-JIRA-005", "Jira 服务端错误（5xx）"),
    FEISHU_TOKEN("QD-FEISHU-001", "飞书访问令牌获取失败"),
    FEISHU_FORBIDDEN("QD-FEISHU-002", "飞书接口无权限（应用权限或表格权限不足）"),
    FEISHU_RATE_LIMIT("QD-FEISHU-003", "飞书接口限流"),
    FEISHU_TIMEOUT("QD-FEISHU-004", "飞书超时或网络不可达"),
    FEISHU_SEND("QD-FEISHU-005", "消息发送失败（单人）"),
    CONFIG_MISSING("QD-CONFIG-001", "必需配置项缺失"),
    CONFIG_INVALID("QD-CONFIG-002", "管理表格内容不合法"),
    DATA_MISSING("QD-DATA-001", "Jira 数据缺少必需字段"),
    SYS("QD-SYS-001", "未预期错误");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }

    public String description() { return description; }
}
