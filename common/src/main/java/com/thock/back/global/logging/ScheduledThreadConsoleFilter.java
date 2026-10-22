package com.thock.back.global.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

// 스케줄러(@Scheduled)는 기본적으로 "scheduling-" 접두사 스레드에서 실행된다.
// 스케줄러/배치성 로그는 콘솔에서만 제외하고(파일 -> Loki 적재는 유지), API 요청 스레드(http-nio-*)의 로그는 그대로 콘솔에 남긴다.
public class ScheduledThreadConsoleFilter extends Filter<ILoggingEvent> {

    private static final String SCHEDULING_THREAD_PREFIX = "scheduling-";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String threadName = event.getThreadName();
        if (threadName != null && threadName.startsWith(SCHEDULING_THREAD_PREFIX)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
