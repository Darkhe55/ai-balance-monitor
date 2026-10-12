package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;

/**
 * 余额查询提供方。每个实现负责一个（或一类）AI 服务，
 * 通过官方接口或可用的响应信息返回余额/配额快照。
 */
public interface BalanceProvider {

    /**
     * 执行一次余额/配额查询。实现应当自行捕获解析错误并转换为
     * 恰当状态的 BalanceSnapshot，不向上抛出未处理异常。
     */
    BalanceSnapshot check(AccountConfig config);
}
