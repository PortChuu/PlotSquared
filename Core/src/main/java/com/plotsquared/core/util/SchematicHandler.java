/*
 *       _____  _       _    _____                                _
 *      |  __ \| |     | |  / ____|                              | |
 *      | |__) | | ___ | |_| (___   __ _ _   _  __ _ _ __ ___  __| |
 *      |  ___/| |/ _ \| __|\___ \ / _` | | | |/ _` | '__/ _ \/ _` |
 *      | |    | | (_) | |_ ____) | (_| | |_| | (_| | | |  __/ (_| |
 *      |_|    |_|\___/ \__|_____/ \__, |\__,_|\__,_|_|  \___|\__,_|
 *                                    | |
 *                                    |_|
 *            PlotSquared plot management system for Minecraft
 *                  Copyright (C) 2020 IntellectualSites
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.plotsquared.core.util;

import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.generator.ClassicPlotWorld;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotArea;
import com.plotsquared.core.plot.schematic.Schematic;
import com.plotsquared.core.queue.LocalBlockQueue;
import com.plotsquared.core.util.task.RunnableVal;
import com.plotsquared.core.util.task.TaskManager;
import com.plotsquared.core.util.uuid.UUIDHandler;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NBTOutputStream;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.MCEditSchematicReader;
import com.sk89q.worldedit.extent.clipboard.io.SpongeSchematicReader;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public abstract class SchematicHandler {
    public static SchematicHandler manager;

    private boolean exportAll = false;

    public boolean exportAll(Collection<Plot> collection, final File outputDir,
        final String namingScheme, final Runnable ifSuccess) {
        if (this.exportAll) {
            return false;
        }
        if (collection.isEmpty()) {
            return false;
        }
        this.exportAll = true;
        final ArrayList<Plot> plots = new ArrayList<>(collection);
        TaskManager.runTask(new Runnable() {
            @Override public void run() {
                if (plots.isEmpty()) {
                    SchematicHandler.this.exportAll = false;
                    TaskManager.runTask(ifSuccess);
                    return;
                }
                Iterator<Plot> i = plots.iterator();
                final Plot plot = i.next();
                i.remove();
                String owner = UUIDHandler.getName(plot.guessOwner());
                if (owner == null) {
                    owner = "unknown";
                }
                final String name;
                if (namingScheme == null) {
                    name =
                        plot.getId().x + ";" + plot.getId().y + ',' + plot.getArea() + ',' + owner;
                } else {
                    name = namingScheme.replaceAll("%owner%", owner)
                        .replaceAll("%id%", plot.getId().toString())
                        .replaceAll("%idx%", plot.getId().x + "")
                        .replaceAll("%idy%", plot.getId().y + "")
                        .replaceAll("%world%", plot.getArea().toString());
                }
                final String directory;
                if (outputDir == null) {
                    directory = Settings.Paths.SCHEMATICS;
                } else {
                    directory = outputDir.getAbsolutePath();
                }
                final Runnable THIS = this;
                SchematicHandler.manager.getCompoundTag(plot, new RunnableVal<CompoundTag>() {
                    @Override public void run(final CompoundTag value) {
                        if (value == null) {
                            MainUtil.sendMessage(null, "&7 - Skipped plot &c" + plot.getId());
                        } else {
                            TaskManager.runTaskAsync(() -> {
                                MainUtil.sendMessage(null, "&6ID: " + plot.getId());
                                boolean result = SchematicHandler.manager
                                    .save(value, directory + File.separator + name + ".schem");
                                if (!result) {
                                    MainUtil
                                        .sendMessage(null, "&7 - Failed to save &c" + plot.getId());
                                } else {
                                    MainUtil.sendMessage(null, "&7 - &a  success: " + plot.getId());
                                }
                                TaskManager.runTask(THIS);
                            });
                        }
                    }
                });
            }
        });
        return true;
    }

    /**
     * Paste a schematic.
     *
     * @param schematic the schematic object to paste
     * @param plot      plot to paste in
     * @param xOffset   offset x to paste it from plot origin
     * @param zOffset   offset z to paste it from plot origin
     */
    public void paste(final Schematic schematic, final Plot plot, final int xOffset,
        final int yOffset, final int zOffset, final boolean autoHeight,
        final RunnableVal<Boolean> whenDone) {

        TaskManager.runTask(() -> {
            if (whenDone != null) {
                whenDone.value = false;
            }
            if (schematic == null) {
                PlotSquared.debug("Schematic == null :|");
                TaskManager.runTask(whenDone);
                return;
            }
            try {
                final LocalBlockQueue queue = plot.getArea().getQueue(false);
                BlockVector3 dimension = schematic.getClipboard().getDimensions();
                final int WIDTH = dimension.getX();
                final int LENGTH = dimension.getZ();
                final int HEIGHT = dimension.getY();
                // Validate dimensions
                CuboidRegion region = plot.getLargestRegion();
                if (((region.getMaximumPoint().getX() - region.getMinimumPoint().getX() + xOffset
                    + 1) < WIDTH) || (
                    (region.getMaximumPoint().getZ() - region.getMinimumPoint().getZ() + zOffset
                        + 1) < LENGTH) || (HEIGHT > 256)) {
                    PlotSquared.debug("Schematic is too large");
                    PlotSquared.debug(
                        "(" + WIDTH + ',' + LENGTH + ',' + HEIGHT + ") is bigger than (" + (
                            region.getMaximumPoint().getX() - region.getMinimumPoint().getX()) + ','
                            + (region.getMaximumPoint().getZ() - region.getMinimumPoint().getZ())
                            + ",256)");
                    TaskManager.runTask(whenDone);
                    return;
                }
                // block type and data arrays
                final Clipboard blockArrayClipboard = schematic.getClipboard();
                // Calculate the optimal height to paste the schematic at
                final int y_offset_actual;
                if (autoHeight) {
                    if (HEIGHT >= 256) {
                        y_offset_actual = yOffset;
                    } else {
                        PlotArea pw = plot.getArea();
                        if (pw instanceof ClassicPlotWorld) {
                            y_offset_actual = yOffset + ((ClassicPlotWorld) pw).PLOT_HEIGHT;
                        } else {
                            y_offset_actual = yOffset + 1 + WorldUtil.IMP
                                .getHighestBlockSynchronous(plot.getWorldName(),
                                    region.getMinimumPoint().getX() + 1,
                                    region.getMinimumPoint().getZ() + 1);
                        }
                    }
                } else {
                    y_offset_actual = yOffset;
                }
                Location pos1 =
                    new Location(plot.getWorldName(), region.getMinimumPoint().getX() + xOffset,
                        y_offset_actual, region.getMinimumPoint().getZ() + zOffset);
                Location pos2 = pos1.clone().add(WIDTH - 1, HEIGHT - 1, LENGTH - 1);
                final int p1x = pos1.getX();
                final int p1z = pos1.getZ();
                final int p2x = pos2.getX();
                final int p2z = pos2.getZ();
                final int bcx = p1x >> 4;
                final int bcz = p1z >> 4;
                final int tcx = p2x >> 4;
                final int tcz = p2z >> 4;

                ChunkManager.chunkTask(pos1, pos2, new RunnableVal<int[]>() {
                    @Override public void run(int[] value) {
                        BlockVector2 chunk = BlockVector2.at(value[0], value[1]);
                        int x = chunk.getX();
                        int z = chunk.getZ();
                        int xxb = x << 4;
                        int zzb = z << 4;
                        int xxt = xxb + 15;
                        int zzt = zzb + 15;
                        if (x == bcx) {
                            xxb = p1x;
                        }
                        if (x == tcx) {
                            xxt = p2x;
                        }
                        if (z == bcz) {
                            zzb = p1z;
                        }
                        if (z == tcz) {
                            zzt = p2z;
                        }
                        // Paste schematic here

                        for (int ry = 0; ry < Math.min(256, HEIGHT); ry++) {
                            int yy = y_offset_actual + ry;
                            if (yy > 255) {
                                continue;
                            }
                            for (int rz = zzb - p1z; rz <= (zzt - p1z); rz++) {
                                for (int rx = xxb - p1x; rx <= (xxt - p1x); rx++) {
                                    int xx = p1x + xOffset + rx;
                                    int zz = p1z + zOffset + rz;
                                    BaseBlock id = blockArrayClipboard
                                        .getFullBlock(BlockVector3.at(rx, ry, rz));
                                    queue.setBlock(xx, yy, zz, id);
                                    if (ry == 0) {
                                        BiomeType biome =
                                            blockArrayClipboard.getBiome(BlockVector2.at(rx, rz));
                                        queue.setBiome(xx, zz, biome);
                                    }
                                }
                            }
                        }
                        queue.enqueue();
                    }
                }, () -> {
                    if (whenDone != null) {
                        whenDone.value = true;
                        whenDone.run();
                    }
                }, 10);
            } catch (Exception e) {
                e.printStackTrace();
                TaskManager.runTask(whenDone);
            }
        });
    }

    public abstract boolean restoreTile(LocalBlockQueue queue, CompoundTag tag, int x, int y,
        int z);

    /**
     * Get a schematic
     *
     * @param name to check
     * @return schematic if found, else null
     */
    public Schematic getSchematic(String name) throws UnsupportedFormatException {
        File parent =
            MainUtil.getFile(PlotSquared.get().IMP.getDirectory(), Settings.Paths.SCHEMATICS);
        if (!parent.exists()) {
            if (!parent.mkdir()) {
                throw new RuntimeException("Could not create schematic parent directory");
            }
        }
        if (!name.endsWith(".schem") && !name.endsWith(".schematic")) {
            name = name + ".schem";
        }
        File file = MainUtil.getFile(PlotSquared.get().IMP.getDirectory(),
            Settings.Paths.SCHEMATICS + File.separator + name);
        if (!file.exists()) {
            file = MainUtil.getFile(PlotSquared.get().IMP.getDirectory(),
                Settings.Paths.SCHEMATICS + File.separator + name);
        }
        return getSchematic(file);
    }

    /**
     * Get an immutable collection containing all schematic names
     *
     * @return Immutable collection with schematic names
     */
    public Collection<String> getSchematicNames() {
        final File parent =
            MainUtil.getFile(PlotSquared.get().IMP.getDirectory(), Settings.Paths.SCHEMATICS);
        final List<String> names = new ArrayList<>();
        if (parent.exists()) {
            final String[] rawNames =
                parent.list((dir, name) -> name.endsWith(".schematic") || name.endsWith(".schem"));
            if (rawNames != null) {
                final List<String> transformed = Arrays.stream(rawNames)
                    //.map(rawName -> rawName.substring(0, rawName.length() - 10))
                    .collect(Collectors.toList());
                names.addAll(transformed);
            }
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Get a schematic
     *
     * @param file to check
     * @return schematic if found, else null
     */
    public Schematic getSchematic(File file) throws UnsupportedFormatException {
        if (!file.exists()) {
            return null;
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format != null) {
            try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
                Clipboard clip = reader.read();
                return new Schematic(clip);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            throw new UnsupportedFormatException(
                "This schematic format is not recognised or supported.");
        }
        return null;
    }

    public Schematic getSchematic(@NotNull URL url) {
        try {
            ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
            InputStream inputStream = Channels.newInputStream(readableByteChannel);
            return getSchematic(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Schematic getSchematic(@NotNull InputStream is) {
        try {
            SpongeSchematicReader schematicReader =
                new SpongeSchematicReader(new NBTInputStream(new GZIPInputStream(is)));
            Clipboard clip = schematicReader.read();
            return new Schematic(clip);
        } catch (IOException ignored) {
            try {
                MCEditSchematicReader schematicReader =
                    new MCEditSchematicReader(new NBTInputStream(new GZIPInputStream(is)));
                Clipboard clip = schematicReader.read();
                return new Schematic(clip);
            } catch (IOException e) {
                e.printStackTrace();
                PlotSquared.debug(is.toString() + " | " + is.getClass().getCanonicalName()
                    + " is not in GZIP format : " + e.getMessage());
            }
        }
        return null;
    }

    public List<String> getSaves(UUID uuid) {
        String rawJSON = "";
        try {
            String website = Settings.Web.URL + "list.php?" + uuid.toString();
            URL url = new URL(website);
            URLConnection connection = new URL(url.toString()).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
                rawJSON = reader.lines().collect(Collectors.joining());
            }
            JSONArray array = new JSONArray(rawJSON);
            List<String> schematics = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String schematic = array.getString(i);
                schematics.add(schematic);
            }
            return schematics;
        } catch (JSONException | IOException e) {
            e.printStackTrace();
            PlotSquared.debug("ERROR PARSING: " + rawJSON);
        }
        return null;
    }

    public void upload(final CompoundTag tag, UUID uuid, String file, RunnableVal<URL> whenDone) {
        if (tag == null) {
            PlotSquared.debug("&cCannot save empty tag");
            TaskManager.runTask(whenDone);
            return;
        }
        MainUtil.upload(uuid, file, "schem", new RunnableVal<OutputStream>() {
            @Override public void run(OutputStream output) {
                try (NBTOutputStream nos = new NBTOutputStream(
                    new GZIPOutputStream(output, true))) {
                    nos.writeNamedTag("Schematic", tag);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }, whenDone);
    }

    /**
     * Saves a schematic to a file path.
     *
     * @param tag  to save
     * @param path to save in
     * @return true if succeeded
     */
    public boolean save(CompoundTag tag, String path) {
        if (tag == null) {
            PlotSquared.debug("&cCannot save empty tag");
            return false;
        }
        try {
            File tmp = MainUtil.getFile(PlotSquared.get().IMP.getDirectory(), path);
            tmp.getParentFile().mkdirs();
            try (NBTOutputStream nbtStream = new NBTOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp)))) {
                nbtStream.writeNamedTag("Schematic", tag);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public abstract void getCompoundTag(String world, Set<CuboidRegion> regions,
        RunnableVal<CompoundTag> whenDone);

    public void getCompoundTag(final Plot plot, final RunnableVal<CompoundTag> whenDone) {
        getCompoundTag(plot.getWorldName(), plot.getRegions(), new RunnableVal<CompoundTag>() {
            @Override public void run(CompoundTag value) {
                whenDone.run(value);
            }
        });
    }


    public static class UnsupportedFormatException extends Exception {
        /**
         * Throw with a message.
         *
         * @param message the message
         */
        public UnsupportedFormatException(String message) {
            super(message);
        }

        /**
         * Throw with a message and a cause.
         *
         * @param message the message
         * @param cause   the cause
         */
        public UnsupportedFormatException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
