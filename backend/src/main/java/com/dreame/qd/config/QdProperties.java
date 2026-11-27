package com.dreame.qd.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 配置项绑定（03 第 6 章）。密钥只来自环境变量，见各 secret 字段。 */
@ConfigurationProperties(prefix = "qd")
public class QdProperties {
    private String timezone = "Asia/Shanghai";
    private Schedule schedule = new Schedule();
    /** 仅联调使用：非空时 17:00 提醒只发给这些 open_id（正式环境保持为空 = 发给全部提醒对象）。 */
    private List<String> reminderTestOpenIds = new ArrayList<>();
    public List<String> getReminderTestOpenIds() { return reminderTestOpenIds; }
    public void setReminderTestOpenIds(List<String> v) { this.reminderTestOpenIds = v; }
    private Jira jira = new Jira();
    private Http http = new Http();
    private Feishu feishu = new Feishu();
    private Storage storage = new Storage();
    private Logging logging = new Logging();

    public static class Schedule {
        private List<String> syncTimes = new ArrayList<>(List.of("09:00", "16:50"));
        private String remindTime = "17:00";
        private String remindCatchupDeadline = "20:00";
        private String calendarCheck = "MON 09:00";
        private String calendarCheckStart = "12-01";
        public List<String> getSyncTimes() { return syncTimes; }
        public void setSyncTimes(List<String> v) { this.syncTimes = v; }
        public String getRemindTime() { return remindTime; }
        public void setRemindTime(String v) { this.remindTime = v; }
        public String getRemindCatchupDeadline() { return remindCatchupDeadline; }
        public void setRemindCatchupDeadline(String v) { this.remindCatchupDeadline = v; }
        public String getCalendarCheck() { return calendarCheck; }
        public void setCalendarCheck(String v) { this.calendarCheck = v; }
        public String getCalendarCheckStart() { return calendarCheckStart; }
        public void setCalendarCheckStart(String v) { this.calendarCheckStart = v; }
    }

    public static class Jira {
        private String baseUrl;
        private String boardJql;
        private Map<String, List<String>> issueTypes = new LinkedHashMap<>();
        private int pageSize = 100;
        private int maxRequestsPerMinute = 90;
        /** 环境变量 QD_JIRA_USERNAME / QD_JIRA_PASSWORD，不入库。 */
        private String username;
        private String password;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getBoardJql() { return boardJql; }
        public void setBoardJql(String v) { this.boardJql = v; }
        public Map<String, List<String>> getIssueTypes() { return issueTypes; }
        public void setIssueTypes(Map<String, List<String>> v) { this.issueTypes = v; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int v) { this.pageSize = v; }
        public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
        public void setMaxRequestsPerMinute(int v) { this.maxRequestsPerMinute = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(15);
        private Duration retryInterval = Duration.ofSeconds(2);
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration v) { this.readTimeout = v; }
        public Duration getRetryInterval() { return retryInterval; }
        public void setRetryInterval(Duration v) { this.retryInterval = v; }
    }

    public static class Feishu {
        private String appId;
        /** 环境变量 QD_FEISHU_APP_SECRET，不入库。 */
        private String appSecret;
        private String baseUrl = "https://open.feishu.cn";
        private Main main = new Main();
        private Admin admin = new Admin();
        private List<String> opsOpenIds = new ArrayList<>();
        private List<String> backupOpenIds = new ArrayList<>();
        public String getAppId() { return appId; }
        public void setAppId(String v) { this.appId = v; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String v) { this.appSecret = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public Main getMain() { return main; }
        public void setMain(Main v) { this.main = v; }
        public Admin getAdmin() { return admin; }
        public void setAdmin(Admin v) { this.admin = v; }
        public List<String> getOpsOpenIds() { return opsOpenIds; }
        public void setOpsOpenIds(List<String> v) { this.opsOpenIds = v; }
        public List<String> getBackupOpenIds() { return backupOpenIds; }
        public void setBackupOpenIds(List<String> v) { this.backupOpenIds = v; }
    }

    public static class Main {
        private String appToken;
        private String taskTableId;
        private String viewMineUrl;
        public String getAppToken() { return appToken; }
        public void setAppToken(String v) { this.appToken = v; }
        public String getTaskTableId() { return taskTableId; }
        public void setTaskTableId(String v) { this.taskTableId = v; }
        /** 可选：副本数据表（"我的未完成任务"，与主表格字段相同，同步时写入同样内容；用于设置不同的行级权限）。为空则不写副本。 */
        private String mineTableId;
        public String getMineTableId() { return mineTableId; }
        public void setMineTableId(String v) { this.mineTableId = v; }
        private String viewAllUrl;
        public String getViewAllUrl() { return viewAllUrl; }
        public void setViewAllUrl(String v) { this.viewAllUrl = v; }
        public String getViewMineUrl() { return viewMineUrl; }
        public void setViewMineUrl(String v) { this.viewMineUrl = v; }
    }

    public static class Admin {
        private String appToken;
        private String excludeTableId;
        private String mappingTableId;
        private String holidayTableId;
        public String getAppToken() { return appToken; }
        public void setAppToken(String v) { this.appToken = v; }
        public String getExcludeTableId() { return excludeTableId; }
        public void setExcludeTableId(String v) { this.excludeTableId = v; }
        public String getMappingTableId() { return mappingTableId; }
        public void setMappingTableId(String v) { this.mappingTableId = v; }
        public String getHolidayTableId() { return holidayTableId; }
        public void setHolidayTableId(String v) { this.holidayTableId = v; }
    }

    public static class Storage {
        private String stateFile = "./data/reminder-state.json";
        public String getStateFile() { return stateFile; }
        public void setStateFile(String v) { this.stateFile = v; }
    }

    public static class Logging {
        private String dir = "./logs";
        private int retentionDays = 7;
        public String getDir() { return dir; }
        public void setDir(String v) { this.dir = v; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int v) { this.retentionDays = v; }
    }

    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule v) { this.schedule = v; }
    public Jira getJira() { return jira; }
    public void setJira(Jira v) { this.jira = v; }
    public Http getHttp() { return http; }
    public void setHttp(Http v) { this.http = v; }
    public Feishu getFeishu() { return feishu; }
    public void setFeishu(Feishu v) { this.feishu = v; }
    public Storage getStorage() { return storage; }
    public void setStorage(Storage v) { this.storage = v; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging v) { this.logging = v; }
}
