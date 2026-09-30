package com.github.guoyyit.flashloanjava.domain.model;

public enum OperationType {
    /** 在池与池之间、或池与借款方虚拟账户之间划转同币种资金。 */
    TRANSFER,
    /** 在单个池内按固定汇率将一种币种兑换为另一种币种。 */
    SWAP
}
