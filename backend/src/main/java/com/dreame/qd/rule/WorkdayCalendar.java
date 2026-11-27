package com.dreame.qd.rule;

import com.dreame.qd.model.Models.Holiday;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/** INT-03 工作日判断（法定工作日）。 */
public final class WorkdayCalendar {
    private WorkdayCalendar() {}

    /** 法定节假日 → false；调休补班 → true；其余周一至周五（含未录入年份）。同日类型冲突以"法定节假日"为准。 */
    public static boolean isWorkday(LocalDate date, List<Holiday> holidays) {
        boolean holiday = false;
        boolean makeup = false;
        for (Holiday h : holidays) {
            if (!h.date().equals(date)) continue;
            if (h.makeup()) makeup = true; else holiday = true;
        }
        if (holiday) return false;
        if (makeup) return true;
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    /** 该年份至少有 1 行记录即视为已录入。 */
    public static boolean isYearEntered(int year, List<Holiday> holidays) {
        return holidays.stream().anyMatch(h -> h.date().getYear() == year);
    }
}
