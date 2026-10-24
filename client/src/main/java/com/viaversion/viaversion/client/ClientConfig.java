/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viaversion.client;

import com.viaversion.viaversion.configuration.AbstractViaConfig;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Via's settings inside a client: defaults plus the few choices a client makes, in memory only. Give it a file name
 * without a directory, so that reloading never tries to create one.
 */
final class ClientConfig extends AbstractViaConfig {

    ClientConfig(final File file, final Logger logger) {
        super(file, logger);
    }

    static Map<String, Object> defaults() {
        final Map<String, Object> values = new HashMap<>();
        values.put("check-for-updates", false);
        // Servers before 1.9 expect a movement packet every tick; newer clients skip idle ones.
        values.put("simulate-pt", true);
        // Chat signing does not apply across versions.
        values.put("enforce-secure-chat", false);
        // Rate limits protect a server from its players; a client has no one to limit.
        values.put("packet-limiter", section("enabled", false));
        values.put("packet-size-limiter", section("enabled", false));
        values.put("eagler-channel-bridge", true);
        values.put("logging", new HashMap<String, Object>());
        return values;
    }

    private static Map<String, Object> section(final String key, final Object value) {
        final Map<String, Object> section = new HashMap<>();
        section.put(key, value);
        return section;
    }

    @Override
    public Map<String, Object> loadConfig(final File location) {
        return defaults();
    }

    @Override
    public void save(final File location, final Map<String, Object> config) {
        // In memory only: a browser client has no file system to write to.
    }

    @Override
    public List<String> getUnsupportedOptions() {
        return List.of();
    }

    @Override
    public boolean isCheckForUpdates() {
        return false;
    }
}
