package org.sokangaming.enjin

import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Singleton(lazy = true)
class CalendarUpdateService implements Runnable {
    final ScheduledExecutorService pool = Executors.newScheduledThreadPool(1)
    final EventScraper scraper = new EventScraper(Config.global)
    ScheduledFuture task

    @Override
    void run() {
        task = pool.scheduleAtFixedRate({scraper.getCalendar(LocalDate.now())}, 0L, 15L, TimeUnit.MINUTES)
    }
}
