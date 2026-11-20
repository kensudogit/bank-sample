package com.example.bank.support;

import com.example.bank.service.JobRunner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * アプリケーション起動時に夜間バッチの定期チェック（10 分ごと）を開始する。
 * 環境変数 BANK_JOBS_ENABLED=true のときだけ動く。1 日 1 回の保証は {@link JobRunner} が DB で行う。
 */
@WebListener
public class BatchScheduler implements ServletContextListener {
    private static final Logger LOG = LogManager.getLogger(BatchScheduler.class);
    private ScheduledExecutorService executor;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        if (!"true".equalsIgnoreCase(System.getenv("BANK_JOBS_ENABLED"))) {
            LOG.info("batch jobs disabled (set BANK_JOBS_ENABLED=true to enable)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bank-batch");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                JobRunner.getDefault().runDue();
                JobRunner.getDefault().scanAml();
            } catch (Throwable t) {   // keep the schedule alive (e.g. DB temporarily down)
                LOG.error("batch check failed", t);
            }
        }, 1, 10, TimeUnit.MINUTES);
        LOG.info("batch jobs enabled: checking every 10 minutes (start {} )", JobRunner.START_TIME);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (executor != null) executor.shutdownNow();
    }
}
