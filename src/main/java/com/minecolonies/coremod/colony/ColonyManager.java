package com.minecolonies.coremod.colony;

import com.google.common.io.Files;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyTagCapability;
import com.minecolonies.api.colony.permissions.Player;
import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.api.compatibility.CompatabilityManager;
import com.minecolonies.api.compatibility.ICompatabilityManager;
import com.minecolonies.api.configuration.Configurations;
import com.minecolonies.api.crafting.IRecipeManager;
import com.minecolonies.api.util.ChunkLoadStorage;
import com.minecolonies.api.util.LanguageHandler;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.Utils;
import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.achievements.ModAchievements;
import com.minecolonies.coremod.blocks.AbstractBlockHut;
import com.minecolonies.coremod.colony.buildings.AbstractBuilding;
import com.minecolonies.coremod.colony.buildings.views.AbstractBuildingView;
import com.minecolonies.coremod.colony.requestsystem.management.manager.StandardRecipeManager;
import com.minecolonies.coremod.network.messages.UpdateChunkCapabilityMessage;
import com.minecolonies.coremod.util.AchievementUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.minecolonies.api.util.constant.Constants.BLOCKS_PER_CHUNK;
import static com.minecolonies.api.util.constant.Constants.HALF_A_CIRCLE;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_COMPATABILITY_MANAGER;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_MISSING_CHUNKS;
import static com.minecolonies.coremod.MineColonies.CLOSE_COLONY_CAP;

/**
 * Singleton class that links colonies to minecraft.
 */
public final class ColonyManager
{
    /**
     * Distance NBT tag.
     */
    private static final String TAG_DISTANCE = "dist";

    /**
     * Tag storing the amount of colonies to NBT.
     */
    private static final String TAG_NEW_COLONIES = "amountOfColonies";

    /**
     * The file name of the minecolonies path.
     */
    public static final String FILENAME_MINECOLONIES_PATH = "minecolonies";

    /**
     * The file name of the minecolonies path.
     */
    public static final String CHUNK_INFO_PATH = FILENAME_MINECOLONIES_PATH + "/chunkInfo";

    /**
     * The file name of the minecolonies.
     */
    private static final String FILENAME_MINECOLONIES = "colonies.dat";

    /**
     * The file name pattern of the minecolonies backup.
     */
    private static final String FILENAME_MINECOLONIES_BACKUP = "colonies-%s.zip";

    /**
     * The tag of the colonies.
     */
    private static final String TAG_COLONIES = "colonies";

    /**
     * Compound tag key for the recipe manager.
     */
    private static final String RECIPE_MANAGER_TAG = "recipeManager";

    /**
     * The tag of the pseudo unique identifier
     */
    private static final String TAG_UUID     = "uuid";

    /**
     * Colony filename.
     */
    public static final String FILENAME_COLONY = "colony%d.dat";

    /**
     * chunk filename.
     */
    private static final String FILENAME_CHUNK = "chunk-%d_%d_%d";

    /**
     * The damage source used to kill citizens.
     */
    private static final DamageSource               CONSOLE_DAMAGE_SOURCE = new DamageSource("Console");

    /**
     * The list of all colonies.
     */
    @NotNull
    private static final ColonyList<Colony>         colonies        = new ColonyList<>();

    /**
     * The list of all colonies by world.
     */
    @NotNull
    private static final Map<Integer, List<Colony>> coloniesByWorld = new HashMap<>();

    /**
     * The list of colony views.
     */
    @NotNull
    private static final ColonyList<ColonyView>     colonyViews = new ColonyList<>();

    /**
     * Amount of worlds loaded.
     */
    private static int numWorldsLoaded;

    /**
     * Whether the colonyManager should persist data.
     */
    private static boolean saveNeeded;

    /**
     * Indicate if a schematic have just been downloaded.
     * Client only
     */
    private static          boolean schematicDownloaded = false;

    /**
     * Recipemanager of this server.
     */
    private static final IRecipeManager recipeManager = new StandardRecipeManager();

    /**
     * Pseudo unique id for the server
     */
    private static volatile UUID    serverUUID          = null;

    /**
     * Removed elements of the list of chunks to load.
     */
    private static int missingChunksToLoad = 0;

    /**
     * Creates a new compatabilityManager.
     */
    private static final ICompatabilityManager compatabilityManager = new CompatabilityManager();

    private ColonyManager()
    {
        //Hides default constructor.
    }

    /**
     * Create a new Colony in the given world and at that location.
     *
     * @param w      World of the colony.
     * @param pos    Coordinate of the center of the colony.
     * @param player the player that creates the colony - owner.
     * @param style the default style of the colony.
     * @return The created colony.
     */
    @NotNull
    public static Colony createColony(@NotNull final World w, final BlockPos pos, @NotNull final EntityPlayer player, @NotNull final String style)
    {
        final Colony colony = colonies.create(w, pos);

        colony.setStyle(style);

        addColonyByWorld(colony);

        final String colonyName = LanguageHandler.format("com.minecolonies.coremod.gui.townHall.defaultName", player.getDisplayNameString());
        colony.setName(colonyName);
        colony.getPermissions().setPlayerRank(player.getGameProfile().getId(), Rank.OWNER, w);

        colony.getStatsManager().triggerAchievement(ModAchievements.achievementGetSupply);
        colony.getStatsManager().triggerAchievement(ModAchievements.achievementTownhall);
        Log.getLogger().info(String.format("New Colony Id: %d by %s", colony.getID(), player.getName()));

        ColonyManager.claimColonyChunks(colony.getWorld(), true, colony.getID(), colony.getCenter(), colony.getDimension());
        ColonyManager.markDirty();
        return colony;
    }

    /**
     * Notify all chunks in the range of the colony about the colony.
     * @param world the world of the colony.
     * @param add remove or add
     */
    public static void claimColonyChunks(final World world, final boolean add, final int id, final BlockPos center, final int dimension)
    {
        final Chunk centralChunk = world.getChunkFromBlockCoords(center);
        if(centralChunk.getCapability(CLOSE_COLONY_CAP, null).getOwningColony() == id && add)
        {
            return;
        }
        final IColonyTagCapability cap = centralChunk.getCapability(CLOSE_COLONY_CAP, null);

        if(add)
        {
            cap.setOwningColony(id);
            cap.addColony(id);
        }
        else
        {
            cap.removeColony(id);
        }
        centralChunk.markDirty();
        MineColonies.getNetwork().sendToAll(new UpdateChunkCapabilityMessage(centralChunk.getCapability(CLOSE_COLONY_CAP, null), centralChunk.x, centralChunk.z));

        final int chunkX = centralChunk.x;
        final int chunkZ = centralChunk.z;

        final int range = Configurations.gameplay.workingRangeTownHallChunks;
        final int buffer = Configurations.gameplay.townHallPaddingChunk;


        claimChunksInRange(id, dimension, add, chunkX, chunkZ, range, buffer);
    }

    /**
     * Claim a number of chunks in a certain range around a position.
     * @param colonyId the colony id.
     * @param dimension the dimension.
     * @param add if claim or unclaim.
     * @param chunkX the chunkX starter position.
     * @param chunkZ the chunkZ starter position.
     * @param range the range.
     * @param buffer the buffer.
     */
    public static void claimChunksInRange(final int colonyId, final int dimension, final boolean add, final int chunkX, final int chunkZ, final int range, final int buffer)
    {
        @NotNull final File chunkDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), CHUNK_INFO_PATH);
        Utils.checkDirectory(chunkDir);
        final int maxRange = range * 2 + buffer;

        for(int i = chunkX - maxRange; i <= chunkX + maxRange; i++)
        {
            for (int j = chunkZ - maxRange; j <= chunkZ + maxRange; j++)
            {
                final boolean owning = i >= chunkX - range && j >= chunkZ - range && i <= chunkX + range && j <= chunkZ + range;
                @NotNull final ChunkLoadStorage newStorage = new ChunkLoadStorage(colonyId, ChunkPos.asLong(i, j), add, dimension, owning);
                @NotNull final File file = new File(chunkDir, String.format(FILENAME_CHUNK, i, j, dimension));
                if (file.exists())
                {
                    @Nullable final NBTTagCompound chunkData = loadNBTFromPath(file);
                    final ChunkLoadStorage storage = new ChunkLoadStorage(chunkData);
                    storage.merge(newStorage);
                    if (storage.isEmpty())
                    {
                        file.delete();
                    }
                    else
                    {
                        saveNBTToPath(file, storage.toNBT());
                        missingChunksToLoad++;
                    }
                }
                else
                {
                    saveNBTToPath(file, newStorage.toNBT());
                    missingChunksToLoad++;
                }
            }
        }
    }

    /**
     * Add a chunk storage to a chunk.
     * @param chunk the chunk to add it to.
     * @param storage the said storage.
     */
    public static void addStorageToChunk(final Chunk chunk, final ChunkLoadStorage storage)
    {
        final IColonyTagCapability cap = chunk.getCapability(CLOSE_COLONY_CAP, null);
        storage.applyToCap(cap);
        chunk.markDirty();
        MineColonies.getNetwork().sendToAll(new UpdateChunkCapabilityMessage(cap, chunk.x, chunk.z));
    }

    /**
     * Load the colony info for a certain chunk.
     * @param chunk the chunk.
     * @param world the worldg to.
     */
    public static void loadChunk(final Chunk chunk, final World world)
    {
        if(missingChunksToLoad > 0)
        {
            @NotNull final File chunkDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), CHUNK_INFO_PATH);
            @NotNull final File file = new File(chunkDir, String.format(FILENAME_CHUNK, chunk.x, chunk.z, world.provider.getDimension()));
            if(file.exists())
            {
                @Nullable final NBTTagCompound chunkData = loadNBTFromPath(file);
                final ChunkLoadStorage storage = new ChunkLoadStorage(chunkData);
                addStorageToChunk(chunk, storage);
                file.delete();
                missingChunksToLoad--;
            }
        }
    }

    private static void addColonyByWorld(final Colony colony)
    {
        if (colony.getDimension() >= 0)
        {
            coloniesByWorld.computeIfAbsent(colony.getDimension(), ArrayList::new).add(colony);
        }
    }

    /**
     * Specify that colonies should be saved.
     */
    public static void markDirty()
    {
        saveNeeded = true;
    }

    /**
     * Delete a colony and kill all citizens/purge all buildings.
     *
     * @param id the colonies id.
     */
    public static void deleteColony(final int id, final boolean canDestroy)
    {
        try
        {
            final Colony colony = getColony(id);
            ColonyManager.claimColonyChunks(colony.getWorld(), false, id, colony.getCenter(), colony.getDimension());
            final Set<World> colonyWorlds = new HashSet<>();
            Log.getLogger().info("Removing citizens for " + id);
            for (final CitizenData citizenData : new ArrayList<>(colony.getCitizenManager().getCitizens()))
            {
                Log.getLogger().info("Kill Citizen " + citizenData.getName());
                citizenData.getCitizenEntity().ifPresent(entityCitizen -> {
                    final World world = entityCitizen.getEntityWorld();
                    entityCitizen.onDeath(CONSOLE_DAMAGE_SOURCE);
                    colonyWorlds.add(world);
                });
            }

            if (canDestroy)
            {
                Log.getLogger().info("Removing buildings for " + id);
                for (final AbstractBuilding building : new ArrayList<>(colony.getBuildingManager().getBuildings().values()))
                {
                    final BlockPos location = building.getLocation();
                    Log.getLogger().info("Delete Building at " + location);
                    building.deconstruct();
                    building.destroy();
                    for (final World world : colonyWorlds)
                    {
                        if (world.getBlockState(location).getBlock() instanceof AbstractBlockHut)
                        {
                            Log.getLogger().info("Found Block, deleting " + world.getBlockState(location).getBlock());
                            world.setBlockToAir(location);
                        }
                    }
                }
            }

            MinecraftForge.EVENT_BUS.unregister(colony.getEventHandler());
            Log.getLogger().info("Deleting colony: " + colony.getID());
            colonies.remove(id);
            coloniesByWorld.get(colony.getDimension()).remove(colony);

            Log.getLogger().info("Done with " + id);
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Deleting Colony " + id + " errored:", e);
        }
        @NotNull final File saveDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), FILENAME_MINECOLONIES_PATH);
        @NotNull final File file = new File(saveDir, String.format(FILENAME_COLONY, id));
        file.delete();
        ColonyManager.markDirty();
    }

    /**
     * Get Colony by UUID.
     *
     * @param id ID of colony.
     * @return Colony with given ID.
     */
    public static Colony getColony(final int id)
    {
        return colonies.get(id);
    }

    /**
     * Syncs the achievements for all colonies.
     */
    public static void syncAllColoniesAchievements()
    {
        colonies.forEach(AchievementUtils::syncAchievements);
    }

    /**
     * Get a AbstractBuilding by a World and coordinates.
     *
     * @param w   World.
     * @param pos Block position.
     * @return AbstractBuilding at the given location.
     */
    public static AbstractBuilding getBuilding(@NotNull final World w, @NotNull final BlockPos pos)
    {
        @Nullable final Colony colony = getColony(w, pos);
        if (colony != null)
        {
            final AbstractBuilding building = colony.getBuildingManager().getBuilding(pos);
            if (building != null)
            {
                return building;
            }
        }

        //  Fallback - there might be a AbstractBuilding for this block, but it's outside of it's owning colony's radius.
        for (@NotNull final Colony otherColony : getColonies(w))
        {
            final AbstractBuilding building = otherColony.getBuildingManager().getBuilding(pos);
            if (building != null)
            {
                return building;
            }
        }

        return null;
    }

    /**
     * Get colony that contains a given coordinate.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return Colony at the given location.
     */
    public static Colony getColony(@NotNull final World w, @NotNull final BlockPos pos)
    {
        final Chunk centralChunk = w.getChunkFromBlockCoords(pos);
        final int id = centralChunk.getCapability(CLOSE_COLONY_CAP, null).getOwningColony();
        if(id == 0)
        {
            return null;
        }
        return getColony(id);
    }

    /**
     * check if a position is too close to another colony.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return true if so.
     */
    public static boolean isTooCloseToColony(@NotNull final World w, @NotNull final BlockPos pos)
    {
        final Chunk centralChunk = w.getChunkFromBlockCoords(pos);
        return !centralChunk.getCapability(CLOSE_COLONY_CAP, null).getAllCloseColonies().isEmpty();
    }

    /**
     * Get all colonies in this world.
     *
     * @param w World.
     * @return a list of colonies.
     */
    @NotNull
    public static List<Colony> getColonies(@NotNull final World w)
    {
        final List<Colony> coloniesInWorld = coloniesByWorld.get(w.provider.getDimension());
        if (coloniesInWorld == null)
        {
            return Collections.emptyList();
        }
        return coloniesInWorld;
    }

    /**
     * Get all colonies in all worlds.
     *
     * @return a list of colonies.
     */
    @NotNull
    public static List<Colony> getColonies()
    {
        return colonies.getCopyAsList();
    }

    /**
     * Get all colonies in all worlds.
     *
     * @param abandonedSince time in hours since the last contact.
     * @return a list of colonies.
     */
    @NotNull
    public static List<Colony> getColoniesAbandonedSince(final int abandonedSince)
    {
        final List<Colony> sortedList = new ArrayList<>();
        for (final Colony colony : colonies.getCopyAsList())
        {
            if (colony.getLastContactInHours() >= abandonedSince)
            {
                sortedList.add(colony);
            }
        }

        return sortedList;
    }

    /**
     * Get a AbstractBuilding by position.
     *
     * @param pos Block position.
     * @return Returns the view belonging to the building at (x, y, z).
     */
    public static AbstractBuildingView getBuildingView(final BlockPos pos)
    {
        //  On client we will just check all known views
        for (@NotNull final ColonyView colony : colonyViews)
        {
            final AbstractBuildingView building = colony.getBuilding(pos);
            if (building != null)
            {
                return building;
            }
        }

        return null;
    }

    /**
     * Side neutral method to get colony.
     * On clients it returns the view.
     * On servers it returns the colony itself.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return View of colony or colony itself depending on side.
     */
    @Nullable
    public static IColony getIColony(@NotNull final World w, @NotNull final BlockPos pos)
    {
        return w.isRemote ? getColonyView(w, pos) : getColony(w, pos);
    }

    /**
     * Get Colony that contains a given (x, y, z).
     *
     * @param w   World.
     * @param pos coordinates.
     * @return returns the view belonging to the colony at x, y, z.
     */
    private static ColonyView getColonyView(@NotNull final World w, @NotNull final BlockPos pos)
    {
        final Chunk centralChunk = w.getChunkFromBlockCoords(pos);
        final int id = centralChunk.getCapability(CLOSE_COLONY_CAP, null).getOwningColony();
        if(id == 0)
        {
            return null;
        }
        return getColonyView(id);
    }

    /**
     * Side neutral method to get colony.
     * On clients it returns the view.
     * On servers it returns the colony itself.
     * {@link #getClosestColony(World, BlockPos)}
     *
     * @param w   World.
     * @param pos Block position.
     * @return View of colony or colony itself depending on side, closest to
     * coordinates.
     */
    @Nullable
    public static IColony getClosestIColony(@NotNull final World w, @NotNull final BlockPos pos)
    {
        return w.isRemote ? getClosestColonyView(w, pos) : getClosestColony(w, pos);
    }

    /**
     * Returns the closest view {@link #getColonyView(World, BlockPos)}.
     *
     * @param w   World.
     * @param pos Block Position.
     * @return View of the closest colony.
     */
    @Nullable
    public static ColonyView getClosestColonyView(@Nullable final World w, @Nullable final BlockPos pos)
    {
        if (w == null || pos == null)
        {
            return null;
        }

        final Chunk chunk = w.getChunkFromBlockCoords(pos);
        final IColonyTagCapability cap = chunk.getCapability(CLOSE_COLONY_CAP, null);
        if(cap.getOwningColony() != 0)
        {
            return getColonyView(cap.getOwningColony());
        }
        else if(!cap.getAllCloseColonies().isEmpty())
        {
            @Nullable ColonyView closestColony = null;
            long closestDist = Long.MAX_VALUE;

            for (@NotNull final int cId : cap.getAllCloseColonies())
            {
                final ColonyView c = getColonyView(cId);
                if (c != null && c.getDimension() == w.provider.getDimension())
                {
                    final long dist = c.getDistanceSquared(pos);
                    if (dist < closestDist)
                    {
                        closestColony = c;
                        closestDist = dist;
                    }
                }
            }
            return closestColony;
        }

        @Nullable ColonyView closestColony = null;
        long closestDist = Long.MAX_VALUE;

        for (@NotNull final ColonyView c : colonyViews)
        {
            if (c.getDimension() == w.provider.getDimension() && c.getCenter() != null)
            {
                final long dist = c.getDistanceSquared(pos);
                if (dist < closestDist)
                {
                    closestColony = c;
                    closestDist = dist;
                }
            }
        }

        return closestColony;
    }

    /**
     * Get closest colony by x,y,z.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return Colony closest to coordinates.
     */
    public static Colony getClosestColony(@NotNull final World w, @NotNull final BlockPos pos)
    {
        final Chunk chunk = w.getChunkFromBlockCoords(pos);
        final IColonyTagCapability cap = chunk.getCapability(CLOSE_COLONY_CAP, null);
        if(cap.getOwningColony() != 0)
        {
            return getColony(cap.getOwningColony());
        }
        else if(!cap.getAllCloseColonies().isEmpty())
        {
            @Nullable Colony closestColony = null;
            long closestDist = Long.MAX_VALUE;

            for (@NotNull final int cId : cap.getAllCloseColonies())
            {
                final Colony c = getColony(cId);
                if (c != null && c.getDimension() == w.provider.getDimension())
                {
                    final long dist = c.getDistanceSquared(pos);
                    if (dist < closestDist)
                    {
                        closestColony = c;
                        closestDist = dist;
                    }
                }
            }
            return closestColony;
        }

        @Nullable Colony closestColony = null;
        long closestDist = Long.MAX_VALUE;

        for (@NotNull final Colony c : getColonies(w))
        {
            if (c.getDimension() == w.provider.getDimension())
            {
                final long dist = c.getDistanceSquared(pos);
                if (dist < closestDist)
                {
                    closestColony = c;
                    closestDist = dist;
                }
            }
        }

        return closestColony;
    }

    /**
     * Side neutral method to get colony.
     * On clients it returns the view.
     * On servers it returns the colony itself.
     * <p>
     * Returns a colony or view with the given Player as owner.
     *
     * @param w     World.
     * @param owner Entity Player.
     * @return IColony belonging to specific player.
     */
    @Nullable
    public static IColony getIColonyByOwner(@NotNull final World w, @NotNull final EntityPlayer owner)
    {
        return getIColonyByOwner(w, w.isRemote ? owner.getUniqueID() : owner.getGameProfile().getId());
    }

    /**
     * Side neutral method to get colony.
     * On clients it returns the view.
     * On servers it returns the colony itself.
     * <p>
     * Returns a colony or view with given Player as owner.
     *
     * @param w     World
     * @param owner UUID of the owner.
     * @return IColony belonging to specific player.
     */
    @Nullable
    public static IColony getIColonyByOwner(@NotNull final World w, final UUID owner)
    {
        return w.isRemote ? getColonyViewByOwner(owner) : getColonyByOwner(owner);
    }

    /**
     * Returns a ColonyView with specific owner.
     *
     * @param owner UUID of the owner.
     * @return ColonyView.
     */
    private static IColony getColonyViewByOwner(final UUID owner)
    {
        for (@NotNull final ColonyView c : colonyViews)
        {
            final Player p = c.getPlayers().get(owner);
            if (p != null && p.getRank().equals(Rank.OWNER))
            {
                return c;
            }
        }

        return null;
    }

    /**
     * Returns a Colony that has the given owner.
     *
     * @param owner UUID of the owner.
     * @return Colony that belong to given owner UUID.
     */
    @Nullable
    private static IColony getColonyByOwner(@Nullable final UUID owner)
    {
        if (owner == null)
        {
            return null;
        }

        return colonies.stream()
                 .filter(c -> owner.equals(c.getPermissions().getOwner()))
                 .findFirst()
                 .orElse(null);
    }

    /**
     * Returns the minimum distance between two town halls, to not make colonies
     * collide.
     *
     * @return Minimum town hall distance.
     */
    public static int getMinimumDistanceBetweenTownHalls()
    {
        //  [TownHall](Radius)+(Padding)+(Radius)[TownHall]
        return (2 * Configurations.gameplay.workingRangeTownHallChunks * BLOCKS_PER_CHUNK) + Configurations.gameplay.townHallPaddingChunk * BLOCKS_PER_CHUNK;
    }

    /**
     * On server tick, tick every Colony.
     * NOTE: Review this for performance.
     *
     * @param event {@link net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent}
     */
    public static void onServerTick(@NotNull final TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            for (@NotNull final Colony c : colonies)
            {
                c.onServerTick(event);
            }

            if (saveNeeded)
            {
                saveColonies(false);
            }
        }
    }

    /**
     * Save all the Colonies.
     */
    private static void saveColonies(final boolean isWorldUnload)
    {
        @NotNull final NBTTagCompound compound = new NBTTagCompound();
        writeToNBT(compound);

        @NotNull final File file = getSaveLocation();
        saveNBTToPath(file, compound);
        @NotNull final File saveDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), FILENAME_MINECOLONIES_PATH);
        for (final Colony colony : colonies)
        {
            if (isWorldUnload)
            {
                final NBTTagCompound colonyCompound = new NBTTagCompound();
                colony.writeToNBT(colonyCompound);
                saveNBTToPath(new File(saveDir, String.format(FILENAME_COLONY, colony.getID())), colonyCompound);
            }
            else
            {
                saveNBTToPath(new File(saveDir, String.format(FILENAME_COLONY, colony.getID())), colony.getColonyTag());
            }
        }
        saveNeeded = false;
    }

    /**
     * Write colonies to NBT data for saving.
     *
     * @param compound NBT-Tag.
     */
    public static void writeToNBT(@NotNull final NBTTagCompound compound)
    {
        //Get the colonies NBT tags and store them in a NBTTagList.
        if (serverUUID != null)
        {
            compound.setUniqueId(TAG_UUID, serverUUID);
        }

        final NBTTagCompound compCompound = new NBTTagCompound();
        compatabilityManager.writeToNBT(compCompound);
        compound.setTag(TAG_COMPATABILITY_MANAGER, compCompound);

        compound.setBoolean(TAG_DISTANCE, true);
        final NBTTagCompound recipeCompound = new NBTTagCompound();
        recipeManager.writeToNBT(recipeCompound);
        compound.setTag(RECIPE_MANAGER_TAG, recipeCompound);
        compound.setInteger(TAG_NEW_COLONIES, colonies.getTopID());
        compound.setInteger(TAG_MISSING_CHUNKS, missingChunksToLoad);
    }

    /**
     * Get save location for Minecolonies data, from the world/save directory.
     *
     * @return Save file for minecolonies.
     */
    @NotNull
    private static File getSaveLocation()
    {
        @NotNull final File saveDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), FILENAME_MINECOLONIES_PATH);
        return new File(saveDir, FILENAME_MINECOLONIES);
    }

    /**
     * Save an NBTTagCompound to a file.  Does so in a safe manner using an
     * intermediate tmp file.
     *
     * @param file     The destination file to write the data to.
     * @param compound The NBTTagCompound to write to the file.
     */
    public static void saveNBTToPath(@Nullable final File file, @NotNull final NBTTagCompound compound)
    {
        try
        {
            if (file != null)
            {
                file.getParentFile().mkdir();
                CompressedStreamTools.safeWrite(compound, file);
            }
        }
        catch (final IOException exception)
        {
            Log.getLogger().error("Exception when saving ColonyManager", exception);
        }
    }

    /**
     * On Client tick, clears views when player left.
     *
     * @param event {@link net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent}.
     */
    public static void onClientTick(@NotNull final TickEvent.ClientTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && Minecraft.getMinecraft().world == null && !colonyViews.isEmpty())
        {
            //  Player has left the game, clear the Colony View cache
            colonyViews.clear();
        }
    }

    /**
     * On world tick, tick every Colony in that world.
     * NOTE: Review this for performance.
     *
     * @param event {@link net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent}.
     */
    public static void onWorldTick(@NotNull final TickEvent.WorldTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            getColonies(event.world).forEach(c -> c.onWorldTick(event));
        }
    }

    /**
     * When a world is loaded, Colonies in that world need to grab the reference
     * to the World. Additionally, when loading the first world, load all
     * colonies.
     *
     * @param world World.
     */
    public static void onWorldLoad(@NotNull final World world)
    {
        if (!world.isRemote && !(world instanceof WorldServerMulti))
        {
            if (numWorldsLoaded == 0)
            {
                if (!backupColonyData())
                {
                    MineColonies.getLogger().error("Failed to save " + FILENAME_MINECOLONIES + " backup!");
                }

                //load the structures when we know where the world is
                Structures.init();

                @NotNull final File file = getSaveLocation();
                @Nullable final NBTTagCompound data = loadNBTFromPath(file);
                if (data != null)
                {
                    readFromNBT(data, world);

                    if (data.hasKey(TAG_NEW_COLONIES))
                    {
                        final int size = data.getInteger(TAG_NEW_COLONIES);

                        @NotNull final File saveDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), FILENAME_MINECOLONIES_PATH);
                        for (int colonyId = 0; colonyId <= size; colonyId++)
                        {
                            @Nullable final NBTTagCompound colonyData = loadNBTFromPath(new File(saveDir, String.format(FILENAME_COLONY, colonyId)));
                            if (colonyData != null)
                            {
                                @NotNull final Colony colony = Colony.loadColony(colonyData, world);
                                colony.getCitizenManager().checkCitizensForHappiness();
                                colonies.add(colony);
                                ColonyManager.claimColonyChunks(colony.getWorld(), true, colony.getID(), colony.getCenter(), colony.getDimension());
                                addColonyByWorld(colony);
                            }
                        }
                    }
                    Log.getLogger().info(String.format("Loaded %d colonies", colonies.getSize()));
                }

                if (serverUUID == null)
                {
                    serverUUID = UUID.randomUUID();
                    Log.getLogger().info(String.format("New Server UUID %s", serverUUID));
                    markDirty();
                }
                else
                {
                    Log.getLogger().info(String.format("Server UUID %s", serverUUID));
                }
            }
            ++numWorldsLoaded;

            for (@NotNull final Colony c : getColonies(world))
            {
                c.onWorldLoad(world);
            }

            world.addEventListener(new ColonyManagerWorldAccess());
        }
    }

    public static boolean backupColonyData()
    {
        if (numWorldsLoaded > 0 && saveNeeded)
        {
            saveColonies(false);
        }

        try(FileOutputStream fos = new FileOutputStream(getBackupSaveLocation(new Date())))
        {
            @NotNull final File saveDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), FILENAME_MINECOLONIES_PATH);
            final ZipOutputStream zos = new ZipOutputStream(fos);

            for (int i = 1; i < colonies.getTopID() + 1; i++)
            {
                @NotNull final File file = new File(saveDir, String.format(FILENAME_COLONY, i));
                if (file.exists())
                {
                    addToZipFile(String.format(FILENAME_COLONY, i), zos, saveDir);
                }
            }
            addToZipFile(getSaveLocation().getName(), zos, saveDir);

            zos.close();
            fos.close();
        }
        catch (final Exception e)
        {
            /**
             * Intentionally not being thrown.
             */
            Log.getLogger().warn("Unable to backup colony data, please contact an administrator");
            return false;
        }

        return true;
    }

    public static void addToZipFile(final String fileName, final ZipOutputStream zos, final File folder)
    {
        final File file = new File(folder, fileName);
        try(FileInputStream fis = new FileInputStream(file))
        {
            zos.putNextEntry(new ZipEntry(fileName));
            Files.copy(file, zos);
            fis.close();
        }
        catch (final Exception e)
        {
            /**
             * Intentionally not being thrown.
             */
            Log.getLogger().warn("Error packing " + fileName + " into the zip.");
        }
    }

    /**
     * Load a file and return the data as an NBTTagCompound.
     *
     * @param file The path to the file.
     * @return the data from the file as an NBTTagCompound, or null.
     */
    private static NBTTagCompound loadNBTFromPath(@Nullable final File file)
    {
        try
        {
            if (file != null && file.exists())
            {
                return CompressedStreamTools.read(file);
            }
        }
        catch (final IOException exception)
        {
            Log.getLogger().error("Exception when loading ColonyManger", exception);
        }
        return null;
    }

    /**
     * Read Colonies from saved NBT data.
     *
     * @param compound NBT Tag.
     */
    public static void readFromNBT(@NotNull final NBTTagCompound compound, @NotNull final World world)
    {
        if(!compound.hasKey(TAG_DISTANCE))
        {
            Configurations.gameplay.workingRangeTownHallChunks =
                    (int) ((Math.cos(45.0 / HALF_A_CIRCLE * Math.PI) * Configurations.gameplay.workingRangeTownHall) / BLOCKS_PER_CHUNK);
        }

        if(!compound.hasKey(TAG_NEW_COLONIES))
        {
            final NBTTagList colonyTags = compound.getTagList(TAG_COLONIES, NBT.TAG_COMPOUND);
            for (int i = 0; i < colonyTags.tagCount(); ++i)
            {
                @NotNull final Colony colony = Colony.loadColony(colonyTags.getCompoundTagAt(i), world);
                colonies.add(colony);
                addColonyByWorld(colony);
            }
        }

        if (compound.hasUniqueId(TAG_UUID))
        {
            serverUUID = compound.getUniqueId(TAG_UUID);
        }

        if(compound.hasKey(TAG_COMPATABILITY_MANAGER))
        {
            compatabilityManager.readFromNBT(compound.getCompoundTag(TAG_COMPATABILITY_MANAGER));
        }
        compatabilityManager.discover(world);

        final NBTTagCompound recipeCompound = compound.getCompoundTag(RECIPE_MANAGER_TAG);
        recipeManager.readFromNBT(recipeCompound);

        missingChunksToLoad = compound.getInteger(TAG_MISSING_CHUNKS);

        Log.getLogger().info(String.format("Loaded %d colonies", colonies.getSize()));
    }

    /**
     * Get save location for Minecolonies backup data, from the world/save
     * directory.
     *
     * @return Save file for minecolonies.
     */
    @NotNull
    private static File getBackupSaveLocation(final Date date)
    {
        @NotNull final File saveDir = new File(DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory(), FILENAME_MINECOLONIES_PATH);
        return new File(saveDir, String.format(FILENAME_MINECOLONIES_BACKUP, new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(date)));
    }

    /**
     * Get the Universal Unique ID for the server.
     *
     * @return the server Universal Unique ID for ther
     */
    public static UUID getServerUUID()
    {
        return serverUUID;
    }

    /**
     * Set the server UUID.
     *
     * @param uuid the universal unique id
     */
    public static void setServerUUID(final UUID uuid)
    {
        serverUUID = uuid;
    }

    /**
     * When a world unloads, all colonies in that world are informed.
     * Additionally, when the last world is unloaded, delete all colonies.
     *
     * @param world World.
     */
    public static void onWorldUnload(@NotNull final World world)
    {
        if (!world.isRemote && !(world instanceof WorldServerMulti))
        {
            if (world.provider.getDimension() == 0)
            {
                saveColonies(true);
            }


            for (@NotNull final Colony c : getColonies(world))
            {
                c.onWorldUnload(world);
            }

            --numWorldsLoaded;
            if (numWorldsLoaded == 0)
            {
                colonies.clear();
                coloniesByWorld.clear();
            }
        }
    }

    /**
     * Sends view message to the right view.
     *
     * @param colonyId          ID of the colony.
     * @param colonyData        {@link ByteBuf} with colony data.
     * @param isNewSubscription whether this is a new subscription or not.
     * @return the response message.
     */
    @Nullable
    public static IMessage handleColonyViewMessage(final int colonyId, @NotNull final ByteBuf colonyData, @NotNull final World world, final boolean isNewSubscription)
    {
        ColonyView view = getColonyView(colonyId);
        if (view == null)
        {
            view = ColonyView.createFromNetwork(colonyId);
            colonyViews.add(view);
        }
        return view.handleColonyViewMessage(colonyData, world, isNewSubscription);
    }

    /**
     * Get ColonyView by ID.
     *
     * @param id ID of colony.
     * @return The ColonyView belonging to the colony.
     */
    public static ColonyView getColonyView(final int id)
    {
        return colonyViews.get(id);
    }

    /**
     * Returns result of {@link ColonyView#handlePermissionsViewMessage(ByteBuf)}
     * if {@link #getColonyView(int)}. gives a not-null result. If {@link
     * #getColonyView(int)} is null, returns null.
     *
     * @param colonyID ID of the colony.
     * @param data     {@link ByteBuf} with colony data.
     * @return result of {@link ColonyView#handlePermissionsViewMessage(ByteBuf)}
     * or null.
     */
    public static IMessage handlePermissionsViewMessage(final int colonyID, @NotNull final ByteBuf data)
    {
        final ColonyView view = getColonyView(colonyID);
        if (view == null)
        {
            Log.getLogger().error(String.format("Colony view does not exist for ID #%d", colonyID));
            return null;
        }
        else
        {
            return view.handlePermissionsViewMessage(data);
        }
    }

    /**
     * Returns result of {@link ColonyView#handleColonyViewCitizensMessage(int,
     * ByteBuf)} if {@link #getColonyView(int)} gives a not-null result. If
     * {@link #getColonyView(int)} is null, returns null.
     *
     * @param colonyId  ID of the colony.
     * @param citizenId ID of the citizen.
     * @param buf       {@link ByteBuf} with colony data.
     * @return result of {@link ColonyView#handleColonyViewCitizensMessage(int,
     * ByteBuf)} or null.
     */
    public static IMessage handleColonyViewCitizensMessage(final int colonyId, final int citizenId, final ByteBuf buf)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view == null)
        {
            return null;
        }
        return view.handleColonyViewCitizensMessage(citizenId, buf);
    }

    /**
     * Returns result of {@link ColonyView#handleColonyViewWorkOrderMessage(ByteBuf)}
     * (int, ByteBuf)} if {@link #getColonyView(int)} gives a not-null result.
     * If {@link #getColonyView(int)} is null, returns null.
     *
     * @param colonyId ID of the colony.
     * @param buf      {@link ByteBuf} with colony data.
     * @return result of {@link ColonyView#handleColonyViewWorkOrderMessage(ByteBuf)}
     * or null.
     */
    public static IMessage handleColonyViewWorkOrderMessage(final int colonyId, final ByteBuf buf)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view == null)
        {
            return null;
        }
        return view.handleColonyViewWorkOrderMessage(buf);
    }

    /**
     * Returns result of {@link ColonyView#handleColonyViewRemoveCitizenMessage(int)}
     * if {@link #getColonyView(int)} gives a not-null result. If {@link
     * #getColonyView(int)} is null, returns null.
     *
     * @param colonyId  ID of the colony.
     * @param citizenId ID of the citizen.
     * @return result of {@link ColonyView#handleColonyViewRemoveCitizenMessage(int)}
     * or null.
     */
    public static IMessage handleColonyViewRemoveCitizenMessage(final int colonyId, final int citizenId)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view != null)
        {
            //  Can legitimately be NULL, because (to keep the code simple and fast), it is
            //  possible to receive a 'remove' notice before receiving the View.
            return view.handleColonyViewRemoveCitizenMessage(citizenId);
        }

        return null;
    }

    /**
     * Returns result of {@link ColonyView#handleColonyBuildingViewMessage(BlockPos,
     * ByteBuf)} if {@link #getColonyView(int)} gives a not-null result. If
     * {@link #getColonyView(int)} is null, returns null.
     *
     * @param colonyId   ID of the colony.
     * @param buildingId ID of the building.
     * @param buf        {@link ByteBuf} with colony data.
     * @return result of {@link ColonyView#handleColonyBuildingViewMessage(BlockPos,
     * ByteBuf)} or null.
     */
    public static IMessage handleColonyBuildingViewMessage(final int colonyId, final BlockPos buildingId, @NotNull final ByteBuf buf)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view != null)
        {
            return view.handleColonyBuildingViewMessage(buildingId, buf);
        }
        else
        {
            Log.getLogger().error(String.format("Colony view does not exist for ID #%d", colonyId));
            return null;
        }
    }

    /**
     * Returns result of {@link ColonyView#handleColonyViewRemoveBuildingMessage(BlockPos)}
     * if {@link #getColonyView(int)} gives a not-null result. If {@link
     * #getColonyView(int)} is null, returns null.
     *
     * @param colonyId   ID of the colony.
     * @param buildingId ID of the building.
     * @return result of {@link ColonyView#handleColonyViewRemoveBuildingMessage(BlockPos)}
     * or null.
     */
    public static IMessage handleColonyViewRemoveBuildingMessage(final int colonyId, final BlockPos buildingId)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view != null)
        {
            //  Can legitimately be NULL, because (to keep the code simple and fast), it is
            //  possible to receive a 'remove' notice before receiving the View.
            return view.handleColonyViewRemoveBuildingMessage(buildingId);
        }

        return null;
    }

    /**
     * Returns result of {@link ColonyView#handleColonyViewRemoveWorkOrderMessage(int)}
     * if {@link #getColonyView(int)} gives a not-null result. If {@link
     * #getColonyView(int)} is null, returns null.
     *
     * @param colonyId    ID of the colony.
     * @param workOrderId ID of the workOrder.
     * @return result of {@link ColonyView#handleColonyViewRemoveWorkOrderMessage(int)}
     * or null.
     */
    public static IMessage handleColonyViewRemoveWorkOrderMessage(final int colonyId, final int workOrderId)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view != null)
        {
            //  Can legitimately be NULL, because (to keep the code simple and fast), it is
            //  possible to receive a 'remove' notice before receiving the View.
            return view.handleColonyViewRemoveWorkOrderMessage(workOrderId);
        }

        return null;
    }

    /**
     * Handle a message about the hapiness.
     * if {@link #getColonyView(int)} gives a not-null result. If {@link
     * #getColonyView(int)} is null, returns null.
     *
     * @param colonyId Id of the colony.
     * @param data     Datas about the hapiness
     * @return result of {@link ColonyView#handleHappinessDataMessage(HappinessData)} or null
     */
    public static IMessage handleHappinessDataMessage(final int colonyId, final HappinessData data)
    {
        final ColonyView view = getColonyView(colonyId);
        if (view != null)
        {
            return view.handleHappinessDataMessage(data);
        }
        return null;
    }

    /**
     * Whether or not a new schematic have been downloaded.
     *
     * @return True if a new schematic have been received.
     */
    public static boolean isSchematicDownloaded()
    {
        return schematicDownloaded;
    }

    /**
     * Set the schematic downloaded
     *
     * @param downloaded True if a new schematic have been received.
     */
    public static void setSchematicDownloaded(final boolean downloaded)
    {
        schematicDownloaded = downloaded;
    }

    /**
     * Check if a given coordinate is inside any other colony.
     *
     * @param world the world to check in.
     * @param pos   the position to check.
     * @return true if a colony has been found.
     */
    public static boolean isCoordinateInAnyColony(@NotNull final World world, final BlockPos pos)
    {
        final Chunk centralChunk = world.getChunkFromBlockCoords(pos);
        return centralChunk.getCapability(CLOSE_COLONY_CAP, null).getOwningColony() != 0;
    }

    /**
     * Get an instance of the compatabilityManager.
     * @return the manager.
     */
    public static ICompatabilityManager getCompatabilityManager()
    {
        return compatabilityManager;
    }

    /**
     * Getter for the recipeManager.
     * @return an IRecipeManager.
     */
    public static IRecipeManager getRecipeManager()
    {
        return recipeManager;
    }
}
