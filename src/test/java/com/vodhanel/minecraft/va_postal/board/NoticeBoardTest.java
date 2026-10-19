package com.vodhanel.minecraft.va_postal.board;

import com.vodhanel.minecraft.va_postal.stats.OfficeStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoticeBoardTest {
    @Test
    void theBusiestBoardFitsItsLinesAndRightAlignsTheCounts() {
        List<OfficeStats.Standing> all = List.of(
                new OfficeStats.Standing("testville", 0, 8, 8, 0, 0L),
                new OfficeStats.Standing("a_very_long_office_name", 0, 120, 100, 0, 0L));
        String[] lines = NoticeBoard.lines(NoticeBoard.Panel.BUSIEST, all);
        assertEquals("&1&lBUSIEST", lines[0]);
        assertTrue(lines[1].startsWith("1 A Very") && lines[1].endsWith(" 120"), lines[1]);
        assertTrue(lines[2].startsWith("2 Testville") && lines[2].endsWith(" 8"), lines[2]);
        assertEquals("", lines[3]);
        for (String l : lines) {
            assertTrue(l.length() <= NoticeBoard.WIDTH || l.startsWith("&"), l);
        }
    }

    @Test
    void anEmptyBoardSaysSo() {
        String[] lines = NoticeBoard.lines(NoticeBoard.Panel.BUSIEST, List.of());
        assertEquals("&8none yet", lines[2]);
    }

    @Test
    void longTextIsCutToASignLine() {
        assertEquals("19/10 Open Broo", NoticeBoard.fit("19/10 Open Brookfield"));
        assertEquals("short", NoticeBoard.fit("short"));
    }

    @Test
    void aNoticeIsShortOnTheSignAndFullInChat() {
        String stored = "19/10|New rates|A letter, to another office: was $5.00, now $9.00";
        assertEquals("19/10 New rates", NoticeBoard.short_notice(stored));
        assertEquals("19/10 A letter, to another office: was $5.00, now $9.00", NoticeBoard.full_notice(stored));
        assertEquals("19 Oct: Open: B", NoticeBoard.short_notice("19 Oct: Open: B")); // stored before notices had two forms
        assertEquals("19 Oct: Open: B", NoticeBoard.full_notice("19 Oct: Open: B"));
    }
}
