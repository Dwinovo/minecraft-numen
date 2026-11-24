package com.dwinovo.numen.permission;

/**
 * 对一只同伴的设置做的一次改动,和"这一下是调松还是调严":她(模型)自己只能往严了调,调松要主人点头。
 * 严与松的判据只在这里:档位往严的一头走是严,往松的一头走是松;名单里记进一项是松(多了一样不用问的),删掉一项是严。
 */
public sealed interface Edit {

    Verb verb();

    /** 套在 {@code now} 上得到的新设置。 */
    Settings applyTo(Settings now);

    /** 套在 {@code now} 上会让设置变松。已经如愿(档位没变、名字已在名单里)不算松。 */
    boolean loosens(Settings now);

    /** 把一种动作的档位定成 {@code tier}。 */
    record SetTier(Verb verb, Tier tier) implements Edit {
        @Override
        public Settings applyTo(Settings now) {
            return now.withTier(verb, tier);
        }

        @Override
        public boolean loosens(Settings now) {
            return now.tier(verb).stricterThan(tier);
        }
    }

    /** 把一个名字记进一种动作的"不用问"名单。 */
    record Add(Verb verb, String name) implements Edit {
        @Override
        public Settings applyTo(Settings now) {
            return now.withName(verb, name);
        }

        @Override
        public boolean loosens(Settings now) {
            return !now.listed(verb, name);
        }
    }

    /** 把一个名字从一种动作的"不用问"名单里删掉。 */
    record Remove(Verb verb, String name) implements Edit {
        @Override
        public Settings applyTo(Settings now) {
            return now.withoutName(verb, name);
        }

        @Override
        public boolean loosens(Settings now) {
            return false;
        }
    }
}
