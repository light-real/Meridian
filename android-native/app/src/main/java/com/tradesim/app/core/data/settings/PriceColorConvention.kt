package com.tradesim.app.core.data.settings

/**
 * 涨跌配色约定。
 * 国际市场（加密货币 / 美股）习惯绿涨红跌，A 股习惯红涨绿跌，做成设置项由用户选。
 */
enum class PriceColorConvention {
    /** 绿涨红跌 */
    GREEN_UP,

    /** 红涨绿跌 */
    RED_UP,
    ;

    companion object {
        fun fromKey(key: String?): PriceColorConvention =
            if (key == RED_UP.name) RED_UP else GREEN_UP
    }
}