package com.dreame.qd.integration.jira;

import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.JiraUser;
import java.util.List;
import java.util.Optional;

/** INT-05：读取 Jira 数据（封装 EXT-J01）。失败抛 QdException（QD-JIRA-*，携带是否可重试）。 */
public interface JiraClient {
    /** 按配置的看板 JQL 取全部未完成 Issue（循环分页，限流）。 */
    List<JiraIssue> fetchUnfinishedIssues();

    /** 按 Jira 账号查询用户（用于把排除名单里的账号对应到飞书人员）。查不到返回空。 */
    Optional<JiraUser> findUser(String account);
}
