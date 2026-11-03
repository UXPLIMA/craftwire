package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.bot.resourcepack.PackPolicy;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;

/** How one bot joins: its name and group, cost profile, client settings, resource pack answers and visibility. */
record JoinSpec(String name, String group, Profile profile, ClientSettings settings, PackPolicy packs, boolean verifyPacks, boolean hidden) {
    static JoinSpec of(String name, SpawnRequest r) {
        return new JoinSpec(name, r.group(), r.profile(), r.settings(), r.resourcePack(), r.verifyPacks(), r.hideBots());
    }
}
