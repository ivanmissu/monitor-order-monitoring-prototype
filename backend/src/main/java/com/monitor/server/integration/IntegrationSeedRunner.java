package com.monitor.server.integration;

import com.monitor.server.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * integration profile 的 ClickHouse 初始化器：
 *
 * <ol>
 *   <li>执行 {@code db/clickhouse-ddl.sql}（做本地化转换：去 Replicated 引擎、去用户授权 ——
 *       本地 chdb 引擎为单副本且无访问管理，集群语义转换见 {@link #localize}）；</li>
 *   <li>若 {@code agg_1d} 为空，执行 {@code db/clickhouse-seed.sql}
 *       （ODS 历史事件经物化视图上卷聚合层，重复启动自动跳过）。</li>
 * </ol>
 *
 * <p>初始化在 {@link ApplicationReadyEvent} 后台线程执行，完成前
 * {@link DemoEventProducer} 不投递事件（见 {@link #ready}）。
 */
@Component
@Profile("integration")
public class IntegrationSeedRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSeedRunner.class);

    /** 种子完成标记：事件生产器据此放行。 */
    private static final AtomicBoolean READY = new AtomicBoolean(false);

    private static final Pattern REPLICATED_REPLACING =
            Pattern.compile("ReplicatedReplacingMergeTree\\([^,]+,[^,]+,\\s*([^)]+)\\)");
    private static final Pattern REPLICATED_AGGREGATING =
            Pattern.compile("ReplicatedAggregatingMergeTree\\([^)]*\\)");
    private static final Pattern REPLICATED_PLAIN =
            Pattern.compile("ReplicatedMergeTree\\([^)]*\\)");

    private final JdbcTemplate jdbc;
    private final MonitorProperties props;

    public IntegrationSeedRunner(DataSource aggDataSource, MonitorProperties props) {
        this.jdbc = new JdbcTemplate(aggDataSource);
        this.props = props;
    }

    public static boolean ready() {
        return READY.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        CompletableFuture.runAsync(() -> {
            try {
                long t0 = System.currentTimeMillis();
                applyDdl();
                boolean fresh = jdbc.queryForObject(
                        "SELECT toInt64(count()) FROM monitor.agg_1d", Long.class) == 0;
                if (fresh && props.getIntegration().getSeedDays() > 0) {
                    applySeed();
                } else {
                    log.info("ClickHouse 已有数据，跳过种子");
                }
                READY.set(true);
                log.info("ClickHouse 初始化完成，耗时 {}ms", System.currentTimeMillis() - t0);
            } catch (Exception ex) {
                log.error("ClickHouse 初始化失败：{}", ex.getMessage(), ex);
            }
        });
    }

    /** DDL 本地化：单副本引擎替换 + 剥离访问管理语句（本地引擎无 Keeper / 无用户体系）。 */
    static String localize(String ddl) {
        String out = REPLICATED_REPLACING.matcher(ddl).replaceAll("ReplacingMergeTree($1)");
        out = REPLICATED_AGGREGATING.matcher(out).replaceAll("AggregatingMergeTree()");
        out = REPLICATED_PLAIN.matcher(out).replaceAll("MergeTree()");
        return out;
    }

    private void applyDdl() throws Exception {
        String ddl = new String(
                // 显式用本类类加载器：本方法经 CompletableFuture.runAsync 运行于
                // ForkJoinPool.commonPool 工作线程，其 TCCL 是系统类加载器，
                // 看不到嵌套 jar 的 BOOT-INF/classes，会 FileNotFoundException。
                new ClassPathResource("db/clickhouse-ddl.sql",
                        IntegrationSeedRunner.class.getClassLoader()).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int n = 0;
        for (String stmt : split(localize(ddl))) {
            String head = stripComments(stmt).toUpperCase();
            if (head.startsWith("CREATE USER") || head.startsWith("GRANT")) {
                continue; // 本地单机引擎无访问管理，权限即数据源由网关与账号约定承担
            }
            jdbc.execute(stmt);
            n++;
        }
        log.info("DDL 已应用（本地化转换后 {} 条语句）", n);
    }

    private void applySeed() throws Exception {
        String seed = new String(
                new ClassPathResource("db/clickhouse-seed.sql",
                        IntegrationSeedRunner.class.getClassLoader()).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        seed = seed.replace("${seedDays}", String.valueOf(props.getIntegration().getSeedDays()))
                .replace("${eventsPerDay}", String.valueOf(props.getIntegration().getSeedEventsPerDay()));
        List<String> stmts = split(seed);
        int n = 0;
        for (String stmt : stmts) {
            long t = System.currentTimeMillis();
            jdbc.execute(stmt);
            n++;
            log.info("种子语句 {}/{} 完成（{}ms）: {}", n, stmts.size(),
                    System.currentTimeMillis() - t, head(stmt));
        }
        log.info("种子数据已写入（{} 条语句）", n);
    }

    private static String head(String stmt) {
        String one = stmt.replaceAll("\\s+", " ").trim();
        return one.substring(0, Math.min(96, one.length()));
    }

    /** 按 ';' 切分语句；种子与 DDL 的字符串字面量中均不含分号（已审查）。 */
    static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        for (String s : sql.split(";")) {
            if (stripComments(s).isBlank()) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    /** 去掉行注释后的语句首行（用于识别 CREATE USER / GRANT 等需跳过的语句）。 */
    static String stripComments(String stmt) {
        StringBuilder sb = new StringBuilder();
        for (String line : stmt.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
