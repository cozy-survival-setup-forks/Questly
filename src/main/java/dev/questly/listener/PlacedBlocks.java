package dev.questly.listener;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers which blocks players placed, so that placing a block and breaking it again gives no progress. The blocks
 * of a chunk are kept in the chunk itself and survive restarts.
 */
public final class PlacedBlocks implements Listener {

    private record ChunkId(UUID world, long chunk) {
    }

    /**
     * The placed blocks of one chunk: where they are inside the chunk, and what kind of block was placed. If the block
     * that stands there later is another kind, the mark is stale (the placed block was washed away, burned...) and is ignored.
     */
    private static final class Marks {
        final Map<Integer, Integer> blocks = new HashMap<>();
        boolean dirty;
    }

    private final NamespacedKey key;
    private final Map<ChunkId, Marks> marks = new HashMap<>();

    public PlacedBlocks(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed");
    }

    private static int pack(Block block) {
        return ((block.getY() + 2048) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private static int kind(Block block) {
        return block.getType().name().hashCode();
    }

    private Marks marksOf(Chunk chunk) {
        return marks.computeIfAbsent(new ChunkId(chunk.getWorld().getUID(), chunk.getChunkKey()), id -> {
            Marks loaded = new Marks();
            int[] saved = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
            if (saved != null) {
                for (int i = 0; i + 1 < saved.length; i += 2) loaded.blocks.put(saved[i], saved[i + 1]);
            }
            return loaded;
        });
    }

    /** True if a player placed the block that stands here now. */
    public boolean isPlaced(Block block) {
        Integer kind = marksOf(block.getChunk()).blocks.get(pack(block));
        return kind != null && kind == kind(block);
    }

    public void mark(Block block) {
        mark(block, kind(block));
    }

    private void mark(Block block, int kind) {
        Marks chunk = marksOf(block.getChunk());
        Integer before = chunk.blocks.put(pack(block), kind);
        if (before == null || before != kind) chunk.dirty = true;
    }

    public void unmark(Block block) {
        Marks chunk = marksOf(block.getChunk());
        if (chunk.blocks.remove(pack(block)) != null) chunk.dirty = true;
    }

    private void write(Chunk chunk, Marks chunkMarks) {
        if (!chunkMarks.dirty) return;
        if (chunkMarks.blocks.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
        } else {
            int[] saved = new int[chunkMarks.blocks.size() * 2];
            int index = 0;
            for (Map.Entry<Integer, Integer> entry : chunkMarks.blocks.entrySet()) {
                saved[index++] = entry.getKey();
                saved[index++] = entry.getValue();
            }
            chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, saved);
        }
        chunkMarks.dirty = false;
    }

    /** Writes the changes of every loaded chunk into the chunks. */
    public void flush() {
        for (var world : org.bukkit.Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Marks chunkMarks = marks.get(new ChunkId(world.getUID(), chunk.getChunkKey()));
                if (chunkMarks != null) write(chunk, chunkMarks);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Marks chunkMarks = marks.remove(new ChunkId(chunk.getWorld().getUID(), chunk.getChunkKey()));
        if (chunkMarks != null) write(chunk, chunkMarks);
    }

    /** A placed block that a piston moves is still a placed block, in its new place. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /** Pulled blocks move toward the piston, the opposite of the way it faces. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection().getOppositeFace());
    }

    private void move(List<Block> blocks, BlockFace direction) {
        List<Block> targets = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        for (Block block : blocks) {
            if (isPlaced(block)) {
                targets.add(block.getRelative(direction));
                kinds.add(kind(block));
                unmark(block);
            }
        }
        for (int i = 0; i < targets.size(); i++) mark(targets.get(i), kinds.get(i));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::unmark);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::unmark);
    }
}
