package banghak.home.authvault.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdpClientWaitTest {

    /** 브라우저 없이 폴링만 검증: visible 판정 JS가 n번째 호출부터 참이 되는 페이지 흉내. */
    private static CdpClient pageWhereElementAppearsAfter(int polls, AtomicInteger calls) {
        return new CdpClient("unused", 0) {
            @Override public String evalString(String js) {
                return calls.incrementAndGet() > polls ? "1" : "0";
            }
        };
    }

    @Test
    void timesOutWhenElementNeverBecomesVisible() {
        AtomicInteger calls = new AtomicInteger();
        CdpClient cdp = pageWhereElementAppearsAfter(Integer.MAX_VALUE, calls);

        long started = System.nanoTime();
        assertThatThrownBy(() -> cdp.waitForSelector("#login-id", 600))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("element not found: #login-id");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isBetween(600L, 3_000L);
        assertThat(calls.get()).isGreaterThan(1);       // 한 번 보고 포기하지 않고 계속 확인했다
    }

    @Test
    void returnsAsSoonAsElementBecomesVisible() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CdpClient cdp = pageWhereElementAppearsAfter(2, calls);

        cdp.waitForSelector("#login-id", 10_000);

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void waitVisibleReportsTimeoutWithoutThrowing() throws Exception {
        CdpClient cdp = pageWhereElementAppearsAfter(Integer.MAX_VALUE, new AtomicInteger());
        assertThat(cdp.waitVisible("#marker", 0)).isFalse();
    }
}
