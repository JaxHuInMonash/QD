package com.dreame.qd.rule;

import com.dreame.qd.model.Models.JiraUser;
import com.dreame.qd.model.Models.PersonRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** INT-08 账号对应（R3）：先按邮箱，再查例外对应表；仍对应不上 → 未对应。纯规则，邮箱查询结果由调用方传入。 */
public final class AccountMapper {
    private AccountMapper() {}

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param users          本次涉及的 Jira 人员（经办人、报告人）
     * @param openIdByEmail  EXT-F06 的结果：小写邮箱 → open_id
     * @param openIdByAccount 例外对应表：小写 Jira 账号 → open_id
     * @return 小写 Jira 账号 → 对应结果
     */
    public static Map<String, PersonRef> map(Collection<JiraUser> users, Map<String, String> openIdByEmail,
                                             Map<String, String> openIdByAccount) {
        Map<String, PersonRef> out = new LinkedHashMap<>();
        for (JiraUser u : users) {
            if (u == null || u.account() == null) continue;
            String acc = normalize(u.account());
            if (out.containsKey(acc)) continue;
            String name = (u.displayName() == null || u.displayName().isBlank()) ? u.account() : u.displayName();
            String openId = openIdByEmail.get(normalize(u.email()));
            if (openId == null) openId = openIdByAccount.get(acc);
            out.put(acc, openId != null
                    ? new PersonRef(u, openId, name, true)
                    : new PersonRef(u, null, name + "（未对应）", false));
        }
        return out;
    }
}
