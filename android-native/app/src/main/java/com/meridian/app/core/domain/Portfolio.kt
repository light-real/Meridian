package com.meridian.app.core.domain

import com.meridian.app.core.model.Account
import com.meridian.app.core.model.MarkedPosition
import java.math.BigDecimal

/**
 * 给 UI 用的仓位快照：
 * - 逐仓直接用仓位自己的强平价
 * - 全仓按当前账户权益估算一个展示用强平价
 */
fun Account.markedPositionsWithLiquidation(
    marks: Map<String, BigDecimal>,
): List<MarkedPosition> =
    markedPositions(marks).map { marked ->
        val position = marked.position
        marked.copy(
            liquidationPrice = if (position.isCross) {
                TradingEngine.crossLiquidationPrice(this, position, marks)
            } else {
                position.liquidationPrice
            },
        )
    }
