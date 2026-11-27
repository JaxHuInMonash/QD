package com.dreame.qd.integration.feishu;

import java.util.Collection;
import java.util.Map;

/** EXT-F06 按邮箱查询飞书用户。 */
public interface ContactGateway {
    /** 返回 小写邮箱 → open_id；查不到的邮箱不在结果中。 */
    Map<String, String> findOpenIdsByEmail(Collection<String> emails);
}
