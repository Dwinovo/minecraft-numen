package com.dwinovo.numen.api.entity;

import net.minecraft.world.effect.MobEffectUtil;

/**
 * 她碰着水时的身体状态片段:在水里、头在不在水下、氧气还剩几秒。随身体状态包推给主人的客户端,挂进
 * {@code <runtime_state>}(和 {@code <worn>} 同一条路,见 {@code NumenPlugins.bodyStateFragments}),模型不必靠报警猜自己的处境。
 * 不在水里一个字都不写,和 {@code <effects>} 同一条纪律。只写事实,不写该怎么办。
 *
 * <p>整段字符串变了才会推包,所以数字的写法管着推送的频率:头在水下按秒写氧气(每秒最多变一次);头在水面上氧气在回(每刻 4 点),
 * 写"满了"或"在回"两种说法,不写数字,免得回气的那几秒每刻一推。
 */
public final class WaterState {

    private WaterState() {}

    /** 这具身体此刻的水况片段;不在水里是空串。 */
    public static String describe(NumenPlayer body) {
        String exempt = "";
        if (body.canBreatheUnderwater() || MobEffectUtil.hasWaterBreathing(body)) {
            exempt = "water breathing";
        } else if (body.getAbilities().invulnerable) {
            exempt = "creative mode";
        }
        return render(body.isInWater(), body.isUnderWater(), body.getAirSupply(), body.getMaxAirSupply(), exempt);
    }

    /**
     * @param inWater    碰着水(不在水里是空串)
     * @param headUnder  头在水下
     * @param air        氧气(刻)
     * @param maxAir     氧气上限(刻)
     * @param exemptWhy  氧气不扣的缘由(水下呼吸类效果、创造模式);空串是照常扣
     */
    public static String render(boolean inWater, boolean headUnder, int air, int maxAir, String exemptWhy) {
        if (!inWater) {
            return "";
        }
        StringBuilder out = new StringBuilder("<water>In water, head ").append(headUnder ? "under water" : "above water");
        if (!exemptWhy.isEmpty()) {
            out.append("; air is not running down (").append(exemptWhy).append(")");
        } else if (headUnder) {
            out.append("; air left ").append(Math.max(0, (air + 19) / 20)).append("s");
        } else {
            out.append("; air ").append(air >= maxAir ? "full" : "refilling");
        }
        return out.append(".</water>").toString();
    }
}
