package com.github.guoyyit.flashloanjava.domain.model;

public enum LoanStatus {
    /** 原子单元全部成功且已持久化提交。 */
    COMMITTED,
    /** 执行或归还失败，单元整体回滚，未产生任何状态变更与手续费。 */
    FAILED
}
