package com.github.guoyyit.flashloanjava.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.flashloanjava.config.FlashLoanProperties;
import com.github.guoyyit.flashloanjava.domain.model.LoanConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class DurableStore {

    /** 供崩溃恢复测试注入：为 true 时下次提交在状态落盘前抛出 {@link SimulatedCrash}。 */
    private volatile boolean crashBeforeNextCommit;

    /**
     * 仅测试使用：落盘提交前被调用（持有 writeLock）。用于把首个事务停在
     * “已在工作副本上执行、尚未提交”阶段，制造并发占用窗口；生产环境为空操作。
     */
    private volatile Runnable beforeCommitHook = () -> { };

    private final FlashLoanProperties properties;
    private final ObjectMapper mapper;
    private Path snapshotFile;

    private final Object writeLock = new Object();

    /** 已提交状态，崩溃恢复后的唯一事实来源；已发布实例不再被原地修改。 */
    private volatile PersistentState committed;

    /** 当前进行中事务的工作副本；为 null 表示没有进行中的写事务。 */
    private PersistentState working;

    public DurableStore(FlashLoanProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    @PostConstruct
    public void init() {
        try {
            Path dir = Path.of(properties.getDataDir());
            Files.createDirectories(dir);
            this.snapshotFile = dir.resolve("state.json");
            if (Files.exists(snapshotFile)) {
                this.committed = mapper.readValue(Files.readAllBytes(snapshotFile), PersistentState.class);
            } else {
                this.committed = initialState();
                durableWrite(committed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化持久化存储", e);
        }
    }

    private PersistentState initialState() {
        PersistentState state = new PersistentState();
        state.setConfig(new LoanConfig(
                1L,
                properties.getDefaultFeeRate(),
                properties.getDefaultMaxLoanAmount(),
                properties.getDefaultMaxSteps()));
        state.setNextLoanId(1L);
        return state;
    }

    /** 开启一个写事务：返回已提交状态的深拷贝，修改仅在 {@link #commitTransaction()} 后可见且持久。 */
    public PersistentState beginTransaction() {
        synchronized (writeLock) {
            boolean interrupted = false;
            while (working != null) {
                try {
                    writeLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            working = deepCopy(committed);
            return working;
        }
    }

    /** 原子提交：状态先 fsync 落盘成功后才对外可见；任何失败都保持旧状态不变。 */
    public void commitTransaction() {
        synchronized (writeLock) {
            if (working == null) {
                throw new IllegalStateException("没有进行中的写事务");
            }
            PersistentState candidate = working;
            beforeCommitHook.run();
            if (crashBeforeNextCommit) {
                crashBeforeNextCommit = false;
                working = null;
                writeLock.notifyAll();
                throw new SimulatedCrash();
            }
            durableWrite(candidate);
            committed = candidate;
            working = null;
            writeLock.notifyAll();
        }
    }

    /** 回滚事务：丢弃工作副本，已提交状态与磁盘均不受影响。 */
    public void rollbackTransaction() {
        synchronized (writeLock) {
            working = null;
            writeLock.notifyAll();
        }
    }

    /**
     * 读取已提交状态的深拷贝。已发布的 committed 永不再被原地修改，
     * 进行中的事务操作的是独立深拷贝，因此读路径无需获取写锁，可与写事务并发。
     */
    public PersistentState readCommitted() {
        return deepCopy(committed);
    }

    /** 仅测试使用：令下一次提交在落盘前抛出 {@link SimulatedCrash}，模拟中途崩溃。 */
    public void setCrashBeforeNextCommit() {
        this.crashBeforeNextCommit = true;
    }

    public void setBeforeCommitHook(Runnable hook) {
        this.beforeCommitHook = hook == null ? () -> { } : hook;
    }

    private void durableWrite(PersistentState state) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(state);
            Path tmp = snapshotFile.resolveSibling("state.json.tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp.toFile())) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            fsyncDirectory(snapshotFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("状态持久化失败，事务未提交", e);
        }
    }

    private void fsyncDirectory(Path dir) {
        // 目录 fsync 用于保证 rename 在断电后仍可见；个别平台不支持时尽力而为。
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(dir)) {
            channel.force(true);
        } catch (IOException ignored) {
            // 平台不支持打开目录时忽略，文件内容本身已 fd.sync()。
        }
    }

    private PersistentState deepCopy(PersistentState state) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(state);
            return mapper.readValue(bytes, PersistentState.class);
        } catch (IOException e) {
            throw new UncheckedIOException("状态深拷贝失败", e);
        }
    }

    /** 模拟“在落盘提交前崩溃”：调用方事务整体丢弃，磁盘仍保持上一个已提交快照。 */
    public static class SimulatedCrash extends RuntimeException {
        public SimulatedCrash() {
            super("simulated crash before durable commit");
        }
    }
}
