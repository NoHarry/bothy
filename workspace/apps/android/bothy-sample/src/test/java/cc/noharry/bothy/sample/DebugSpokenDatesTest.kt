package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugSpokenDatesTest {
    @Test
    fun datesAreSaidAsDates() {
        assertEquals("这周末10月24日、25日有演出", "这周末10/24、25日有演出".spokenDates())
        assertEquals("10月20日到10月24日都开", "10/20到10/24都开".spokenDates())
        assertEquals("10月24日-10月25日两天", "10/24-10/25两天".spokenDates())
        assertEquals("2026年10月24日出发", "2026-10-24出发".spokenDates())
        assertEquals("2026年1月5日到", "2026/01/05到".spokenDates())
        assertEquals("3月4日（周六）见", "3/4（周六）见".spokenDates())
        assertEquals("1月25日回来", "1/25回来".spokenDates())
    }

    @Test
    fun numbersThatAreNotDatesStay() {
        assertEquals("明天30/22°，多云", "明天30/22°，多云".spokenDates())
        assertEquals("最高12/8℃", "最高12/8℃".spokenDates())
        assertEquals("票价80/180/280元", "票价80/180/280元".spokenDates())
        assertEquals("评分4.7/5", "评分4.7/5".spokenDates())
        assertEquals("走了1/3的路", "走了1/3的路".spokenDates())
        assertEquals("打分8/10分", "打分8/10分".spokenDates())
        assertEquals("10:30/14:00两场", "10:30/14:00两场".spokenDates())
        assertEquals("评分10/10，进度11/12", "评分10/10，进度11/12".spokenDates())
        assertEquals("最低12/8 ℃", "最低12/8 ℃".spokenDates())
    }
}
