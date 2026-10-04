package com.evops.coldchain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * V8 报价金额统一口径（全系统唯一的算钱入口，报价/改价/锁价快照都走这里）：
 * 不含税小计 subtotal = 基础运费 base + 附加费合计 surcharge；
 * 税额 tax = subtotal × 税率（四舍五入保留 2 位小数）；
 * 含税总价 total = subtotal + tax。
 * 调用方只允许提交 base/附加费明细/税率，不允许直接给一口价总价。
 */
public final class PriceMath {

    public static final int MONEY_SCALE = 2;

    private PriceMath() {
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 附加费合计（逐项四舍五入后求和） */
    public static BigDecimal sumSurcharge(java.util.List<BigDecimal> items) {
        BigDecimal sum = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (items != null) {
            for (BigDecimal item : items) {
                sum = sum.add(money(item));
            }
        }
        return sum;
    }

    public static BigDecimal subtotal(BigDecimal base, BigDecimal surcharge) {
        return money(base).add(money(surcharge));
    }

    public static BigDecimal tax(BigDecimal subtotal, BigDecimal taxRate) {
        return money(subtotal.multiply(taxRate));
    }

    public static BigDecimal total(BigDecimal subtotal, BigDecimal tax) {
        return money(subtotal).add(money(tax));
    }

    /** 完整拆项结果 */
    public static final class Breakdown {
        public final BigDecimal base;
        public final BigDecimal surcharge;
        public final BigDecimal subtotal;
        public final BigDecimal taxRate;
        public final BigDecimal tax;
        public final BigDecimal total;

        Breakdown(BigDecimal base, BigDecimal surcharge, BigDecimal taxRate) {
            this.base = money(base);
            this.surcharge = money(surcharge);
            this.subtotal = subtotal(this.base, this.surcharge);
            this.taxRate = taxRate;
            this.tax = tax(this.subtotal, taxRate);
            this.total = total(this.subtotal, this.tax);
        }
    }
}
