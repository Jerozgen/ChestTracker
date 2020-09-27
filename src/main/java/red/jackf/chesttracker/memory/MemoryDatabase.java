package red.jackf.chesttracker.memory;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.realms.dto.RealmsServer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.ChestTracker;
import red.jackf.chesttracker.mixins.AccessorMinecraftServer;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Environment(EnvType.CLIENT)
public class MemoryDatabase {
    private static final CompoundTag FULL_DURABILITY_TAG = new CompoundTag();
    @Nullable
    private static MemoryDatabase currentDatabase = null;

    static {
        FULL_DURABILITY_TAG.putInt("Damage", 0);
    }

    private transient final String id;
    private Map<Identifier, Map<BlockPos, Memory>> locations = new HashMap<>();
    private transient Map<Identifier, Map<BlockPos, Memory>> namedLocations = new HashMap<>();

    private MemoryDatabase(String id) {
        this.id = id;
    }

    public static void clearCurrent() {
        if (currentDatabase != null) {
            currentDatabase.save();
            currentDatabase = null;
        }
    }

    @Nullable
    public static MemoryDatabase getCurrent() {
        String id = getUsableId();
        if (id == null) return null;
        if (currentDatabase != null && currentDatabase.getId().equals(id)) return currentDatabase;
        MemoryDatabase database = new MemoryDatabase(id);
        database.load();
        currentDatabase = database;
        ChestTracker.LOGGER.info("Loaded " + id);
        return database;
    }

    @Nullable
    private static String getUsableId() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String id = null;
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            id = "singleplayer-" + MemoryUtils.getSingleplayerName(((AccessorMinecraftServer) mc.getServer()).getSession());
        } else if (mc.isConnectedToRealms()) {
            RealmsServer server = MemoryUtils.getLastRealmsServer();
            if (server == null) return null;
            id = "realms-" + MemoryUtils.makeFileSafe(server.owner + "-" + server.getName());
        } else {
            ClientPlayNetworkHandler cpnh = mc.getNetworkHandler();
            if (cpnh != null && cpnh.getConnection().isOpen()) {
                SocketAddress address = cpnh.getConnection().getAddress();
                if (address instanceof InetSocketAddress) {
                    InetSocketAddress inet = ((InetSocketAddress) address);
                    id = "multiplayer-" + inet.getAddress() + (inet.getPort() == 25565 ? "" : "-" + inet.getPort());
                } else {
                    id = "multiplayer-" + MemoryUtils.makeFileSafe(address.toString());
                }
            }
        }

        return id;
    }

    public Set<Identifier> getDimensions() {
        return locations.keySet();
    }

    public String getId() {
        return id;
    }

    public void save() {
        Path savePath = getFilePath();
        try {
            try {
                Files.createDirectory(savePath.getParent());
            } catch (FileAlreadyExistsException ignored) {
            }
            FileWriter writer = new FileWriter(savePath.toString());
            GsonHandler.get().toJson(locations, writer);
            writer.flush();
            writer.close();
            ChestTracker.LOGGER.info("Saved data for " + id);
        } catch (IOException ex) {
            ChestTracker.LOGGER.error("Error saving file for " + this.id);
            ChestTracker.LOGGER.error(ex);
        }
    }

    public void load() {
        Path loadPath = getFilePath();
        try {
            if (Files.exists(loadPath)) {
                ChestTracker.LOGGER.info("Found data for " + id);
                FileReader reader = new FileReader(loadPath.toString());
                this.locations = GsonHandler.get().fromJson(new JsonReader(reader), new TypeToken<Map<Identifier, Map<BlockPos, Memory>>>() {
                }.getType());
                this.generateNamedLocations();
            } else {
                ChestTracker.LOGGER.info("No data found for " + id);
                this.locations = new HashMap<>();
                this.namedLocations = new HashMap<>();
            }
        } catch (JsonParseException | IOException ex) {
            ChestTracker.LOGGER.error("Error reading file for " + this.id);
            ChestTracker.LOGGER.error(ex);
        }
    }

    // Creates namedLocations list from current locations list.
    private void generateNamedLocations() {
        Map<Identifier, Map<BlockPos, Memory>> namedLocations = new HashMap<>();
        for (Identifier worldId : this.locations.keySet()) {
            Map<BlockPos, Memory> newMap = namedLocations.computeIfAbsent(worldId, id -> new HashMap<>());
            this.locations.get(worldId).forEach(((pos, memory) -> {
                if (memory.getTitle() != null) newMap.put(pos, memory);
            }));
        }
        this.namedLocations = namedLocations;
    }

    @NotNull
    public Path getFilePath() {
        return FabricLoader.getInstance().getGameDir().resolve("chesttracker").resolve(id + ".json");
    }

    public List<ItemStack> getItems(Identifier worldId) {
        if (locations.containsKey(worldId)) {
            Map<LightweightStack, Integer> count = new HashMap<>();
            Map<BlockPos, Memory> location = locations.get(worldId);
            location.forEach((pos, memory) -> memory.getItems().forEach(stack -> {
                LightweightStack lightweightStack = new LightweightStack(stack.getItem(), stack.getTag());
                count.merge(lightweightStack, stack.getCount(), Integer::sum);
            }));
            List<ItemStack> results = new ArrayList<>();
            count.forEach(((lightweightStack, integer) -> {
                ItemStack stack = new ItemStack(lightweightStack.getItem(), integer);
                stack.setTag(lightweightStack.getTag());
                results.add(stack);
            }));
            return results;
        } else {
            return Collections.emptyList();
        }
    }

    public Collection<Memory> getAllMemories(Identifier worldId) {
        if (locations.containsKey(worldId)) {
            return locations.get(worldId).values();
        } else {
            return Collections.emptyList();
        }
    }

    public Collection<Memory> getNamedMemories(Identifier worldId) {
        if (namedLocations.containsKey(worldId)) {
            return namedLocations.get(worldId).values();
        } else {
            return Collections.emptyList();
        }
    }

    public void mergeItems(Identifier worldId, Memory memory, Collection<BlockPos> toRemove) {
        if (locations.containsKey(worldId)) {
            Map<BlockPos, Memory> map = locations.get(worldId);
            map.remove(memory.getPosition());
            toRemove.forEach(map::remove);
        }
        if (namedLocations.containsKey(worldId)) {
            Map<BlockPos, Memory> map = namedLocations.get(worldId);
            map.remove(memory.getPosition());
            toRemove.forEach(map::remove);
        }
        mergeItems(worldId, memory);
    }

    public void mergeItems(Identifier worldId, Memory memory) {
        if (memory.getItems().size() > 0 || memory.getTitle() != null) {
            addItem(worldId, memory, locations);
            if (memory.getTitle() != null) {
                addItem(worldId, memory, namedLocations);
            }
        }
    }

    private void addItem(Identifier worldId, Memory memory, Map<Identifier, Map<BlockPos, Memory>> namedLocations) {
        Map<BlockPos, Memory> namedMap = namedLocations.computeIfAbsent(worldId, (identifier -> new HashMap<>()));
        namedMap.put(memory.getPosition(), memory);
    }

    public void removePos(Identifier worldId, BlockPos pos) {
        Map<BlockPos, Memory> location = locations.get(worldId);
        if (location != null) location.remove(pos);
        Map<BlockPos, Memory> namedLocation = namedLocations.get(worldId);
        if (namedLocation != null) namedLocation.remove(pos);
    }

    public List<Memory> findItems(ItemStack toFind, Identifier worldId) {
        List<Memory> found = new ArrayList<>();
        Map<BlockPos, Memory> location = locations.get(worldId);
        if (location != null) {
            for (Map.Entry<BlockPos, Memory> entry : location.entrySet()) {
                if (entry.getKey() != null) {
                    if (entry.getValue().getItems().stream()
                        .anyMatch(candidate -> MemoryUtils.areStacksEquivalent(toFind, candidate, toFind.getTag() == null || toFind.getTag().equals(FULL_DURABILITY_TAG)))) {
                        if (MemoryUtils.checkExistsInWorld(entry.getValue())) {
                            found.add(entry.getValue());
                        } else {
                            // Remove if it's disappeared.
                            if (MemoryDatabase.getCurrent() != null) MemoryDatabase.getCurrent().removePos(worldId, entry.getKey());
                        }
                    }
                }
            }
        }
        return found;
    }

    public void clearDimension(Identifier currentWorldId) {
        locations.remove(currentWorldId);
        namedLocations.remove(currentWorldId);
    }
}
