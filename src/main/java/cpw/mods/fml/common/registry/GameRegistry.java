/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     cpw - implementation
 */

package cpw.mods.fml.common.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.apache.logging.log4j.Level;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.IFuelHandler;
import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderException;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.ObfuscationReflectionHelper;

public class GameRegistry
{
    private static Set<IWorldGenerator> worldGenerators = Sets.newHashSet();
    private static Map<IWorldGenerator, Integer> worldGeneratorIndex = Maps.newHashMap();
    private static List<IFuelHandler> fuelHandlers = Lists.newArrayList();
    private static List<IWorldGenerator> sortedGeneratorList;

    /**
     * Register a world generator - something that inserts new block types into the world
     *
     * @param generator the generator
     * @param modGenerationWeight a weight to assign to this generator. Heavy weights tend to sink to the bottom of
     * list of world generators (i.e. they run later)
     */
    public static void registerWorldGenerator(IWorldGenerator generator, int modGenerationWeight)
    {
        worldGenerators.add(generator);
        worldGeneratorIndex.put(generator, modGenerationWeight);
        if (sortedGeneratorList != null)
        {
            sortedGeneratorList = null;
        }
    }

    /**
     * Callback hook for world gen - if your mod wishes to add extra mod related generation to the world
     * call this
     *
     * @param chunkX
     * @param chunkZ
     * @param world
     * @param chunkGenerator
     * @param chunkProvider
     */
    public static void generateWorld(int chunkX, int chunkZ, World world, IChunkProvider chunkGenerator, IChunkProvider chunkProvider)
    {
        if (sortedGeneratorList == null)
        {
            computeSortedGeneratorList();
        }
        long worldSeed = world.getSeed();
        Random fmlRandom = new Random(worldSeed);
        long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        long chunkSeed = (xSeed * chunkX + zSeed * chunkZ) ^ worldSeed;

        for (IWorldGenerator generator : sortedGeneratorList)
        {
            fmlRandom.setSeed(chunkSeed);
            generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        }
    }

    private static void computeSortedGeneratorList()
    {
        ArrayList<IWorldGenerator> list = Lists.newArrayList(worldGenerators);
        Collections.sort(list, new Comparator<IWorldGenerator>() {
            @Override
            public int compare(IWorldGenerator o1, IWorldGenerator o2)
            {
                return Ints.compare(worldGeneratorIndex.get(o1), worldGeneratorIndex.get(o2));
            }
        });
        sortedGeneratorList = ImmutableList.copyOf(list);
    }

    /**
     * Register an item with the item registry with a custom name : this allows for easier server->client resolution
     *
     * @param item The item to register
     * @param name The mod-unique name of the item
     */
    public static void registerItem(net.minecraft.item.Item item, String name)
    {
        registerItem(item, name, null);
    }

    /**
     * Register the specified Item with a mod specific name : overrides the standard type based name
     * @param item The item to register
     * @param name The mod-unique name to register it as - null will remove a custom name
     * @param modId deprecated, unused
     * where one mod should "own" all the blocks of all the mods, null defaults to the active mod
     */
    public static Item registerItem(Item item, String name, String modId)
    {
        GameData.getMain().registerItem(item, name);
        return item;
    }


    /**
     * Add a forced persistent substitution alias for the block or item to another block or item. This will have
     * the effect of using the substituted block or item instead of the original, where ever it is
     * referenced.
     *
     * @param nameToSubstitute The name to link to (this is the NEW block or item)
     * @param type The type (Block or Item)
     * @param object a NEW instance that is type compatible with the existing instance
     * @throws ExistingSubstitutionException if someone else has already registered an alias either from or to one of the names
     * @throws IncompatibleSubstitutionException if the substitution is incompatible
     */
    public static void addSubstitutionAlias(String nameToSubstitute, GameRegistry.Type type, Object object) throws ExistingSubstitutionException
    {
        GameData.getMain().registerSubstitutionAlias(nameToSubstitute, type, object);
    }

    /**
     * Register a block with the specified mod specific name
     * @param block The block to register
     * @param name The mod-unique name to register it as, will get prefixed by your modid.
     */
    public static Block registerBlock(Block block, String name)
    {
        return registerBlock(block, ItemBlock.class, name);
    }

    /**
     * Register a block with the world, with the specified item class and block name
     * @param block The block to register
     * @param itemclass The item type to register with it : null registers a block without associated item.
     * @param name The mod-unique name to register it as, will get prefixed by your modid.
     */
    public static Block registerBlock(Block block, Class<? extends ItemBlock> itemclass, String name)
    {
        return registerBlock(block, itemclass, name, new Object[]{});
    }

    /**
     * @deprecated Use the registerBlock version without the modId parameter instead.
     */
    @Deprecated
    public static Block registerBlock(Block block, Class<? extends ItemBlock> itemclass, String name, String modId, Object... itemCtorArgs)
    {
        return registerBlock(block, itemclass, name, itemCtorArgs);
    }

    /**
     * Register a block with the world, with the specified item class, block name and owning modId
     * @param block The block to register
     * @param itemclass The item type to register with it : null registers a block without associated item.
     * @param name The mod-unique name to register it as, will get prefixed by your modid.
     * @param itemCtorArgs Arguments to pass (after the required {@code Block} parameter) to the ItemBlock constructor (optional).
     */
    public static Block registerBlock(Block block, Class<? extends ItemBlock> itemclass, String name, Object... itemCtorArgs)
    {
        if (Loader.instance().isInState(LoaderState.CONSTRUCTING))
        {
            FMLLog.warning("The mod %s is attempting to register a block whilst it it being constructed. This is bad modding practice - please use a proper mod lifecycle event.", Loader.instance().activeModContainer());
        }
        try
        {
            assert block != null : "registerBlock: block cannot be null";
            ItemBlock i = null;
            if (itemclass != null)
            {
                Class<?>[] ctorArgClasses = new Class<?>[itemCtorArgs.length + 1];
                ctorArgClasses[0] = Block.class;
                for (int idx = 1; idx < ctorArgClasses.length; idx++)
                {
                    ctorArgClasses[idx] = itemCtorArgs[idx-1].getClass();
                }
                Constructor<? extends ItemBlock> itemCtor = itemclass.getConstructor(ctorArgClasses);
                i = itemCtor.newInstance(ObjectArrays.concat(block, itemCtorArgs));
            }
            // block registration has to happen first
            GameData.getMain().registerBlock(block, name);
            if (i != null)
            {
                GameData.getMain().registerItem(i, name);
            }
            return block;
        }
        catch (Exception e)
        {
            FMLLog.log(Level.ERROR, e, "Caught an exception during block registration");
            throw new LoaderException(e);
        }
    }

    public static void addRecipe(ItemStack output, Object... params)
    {
        addShapedRecipe(output, params);
    }

    public static IRecipe addShapedRecipe(ItemStack output, Object... params)
    {
        return CraftingManager.getInstance().addRecipe(output, params);
    }

    public static void addShapelessRecipe(ItemStack output, Object... params)
    {
        CraftingManager.getInstance().addShapelessRecipe(output, params);
    }

    @SuppressWarnings("unchecked")
    public static void addRecipe(IRecipe recipe)
    {
        CraftingManager.getInstance().getRecipeList().add(recipe);
    }

    public static void addSmelting(Block input, ItemStack output, float xp)
    {
        FurnaceRecipes.smelting().func_151393_a(input, output, xp);
    }

    public static void addSmelting(Item input, ItemStack output, float xp)
    {
        FurnaceRecipes.smelting().func_151396_a(input, output, xp);
    }

    public static void addSmelting(ItemStack input, ItemStack output, float xp)
    {
        FurnaceRecipes.smelting().func_151394_a(input, output, xp);
    }

    public static void registerTileEntity(Class<? extends TileEntity> tileEntityClass, String id)
    {
        TileEntity.addMapping(tileEntityClass, id);
    }

    /**
     * Register a tile entity, with alternative TileEntity identifiers. Use with caution!
     * This method allows for you to "rename" the 'id' of the tile entity.
     *
     * @param tileEntityClass The tileEntity class to register
     * @param id The primary ID, this will be the ID that the tileentity saves as
     * @param alternatives A list of alternative IDs that will also map to this class. These will never save, but they will load
     */
    public static void registerTileEntityWithAlternatives(Class<? extends TileEntity> tileEntityClass, String id, String... alternatives)
    {
        TileEntity.addMapping(tileEntityClass, id);
        Map<String,Class<?>> teMappings = ObfuscationReflectionHelper.getPrivateValue(TileEntity.class, null, "field_" + "145855_i", "nameToClassMap");
        for (String s: alternatives)
        {
            if (!teMappings.containsKey(s))
            {
                teMappings.put(s, tileEntityClass);
            }
        }
    }

    public static void registerFuelHandler(IFuelHandler handler)
    {
        fuelHandlers.add(handler);
    }
    public static int getFuelValue(ItemStack itemStack)
    {
        int fuelValue = 0;
        for (IFuelHandler handler : fuelHandlers)
        {
            fuelValue = Math.max(fuelValue, handler.getBurnTime(itemStack));
        }
        return fuelValue;
    }

    /**
     * Look up a mod block in the global "named item list"
     * @param modId The modid owning the block
     * @param name The name of the block itself
     * @return The block or null if not found
     */
    public static Block findBlock(String modId, String name)
    {
        return GameData.findBlock(modId, name);
    }

    /**
     * Look up a mod item in the global "named item list"
     * @param modId The modid owning the item
     * @param name The name of the item itself
     * @return The item or null if not found
     */
    public static Item findItem(String modId, String name)
    {
        return GameData.findItem(modId, name);
    }

    /**
     * Manually register a custom item stack with FML for later tracking. It is automatically scoped with the active modid
     *
     * @param name The name to register it under
     * @param itemStack The itemstack to register
     */
    public static void registerCustomItemStack(String name, ItemStack itemStack)
    {
        GameData.registerCustomItemStack(name, itemStack);
    }
    /**
     * Lookup an itemstack based on mod and name. It will create "default" itemstacks from blocks and items if no
     * explicit itemstack is found.
     *
     * If it is built from a block, the metadata is by default the "wildcard" value.
     *
     * Custom itemstacks can be dumped from minecraft by setting the system property fml.dumpRegistry to true
     * (-Dfml.dumpRegistry=true on the command line will work)
     *
     * @param modId The modid of the stack owner
     * @param name The name of the stack
     * @param stackSize The size of the stack returned
     * @return The custom itemstack or null if no such itemstack was found
     */
    public static ItemStack findItemStack(String modId, String name, int stackSize)
    {
        ItemStack foundStack = GameData.findItemStack(modId, name);
        if (foundStack != null)
        {
            ItemStack is = foundStack.copy();
            is.stackSize = Math.min(stackSize, is.getMaxStackSize());
            return is;
        }
        return null;
    }

    public static final class UniqueIdentifier
    {
        public final String modId;
        public final String name;
        UniqueIdentifier(String modId, String name)
        {
            this.modId = modId;
            this.name = name;
        }

        public UniqueIdentifier(String string)
        {
            String[] parts = string.split(":");
            this.modId = parts[0];
            this.name = parts[1];
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj == null) return false;
            if (obj.getClass() != this.getClass()) return false;
            final UniqueIdentifier other = (UniqueIdentifier) obj;
            return Objects.equal(modId, other.modId) && Objects.equal(name, other.name);
        }

        @Override
        public int hashCode()
        {
            return Objects.hashCode(modId, name);
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s", modId, name);
        }
    }

    public static enum Type {
        BLOCK
        {
            @Override
            public FMLControlledNamespacedRegistry<?> getRegistry() {
                return GameData.getBlockRegistry();
            }
        },
        ITEM
        {
            @Override
            public FMLControlledNamespacedRegistry<?> getRegistry() {
                return GameData.getItemRegistry();
            }
        };

        public abstract FMLControlledNamespacedRegistry<?> getRegistry();
    }
    /**
     * Look up the mod identifier data for a block.
     * Returns null if there is no mod specified mod identifier data, or it is part of a
     * custom itemstack definition {@link #registerCustomItemStack}
     *
     * Note: uniqueness and persistence is only guaranteed by mods using the game registry
     * correctly.
     *
     * @param block to lookup
     * @return a {@link UniqueIdentifier} for the block or null
     */
    public static UniqueIdentifier findUniqueIdentifierFor(Block block)
    {
        return GameData.getUniqueName(block);
    }
    /**
     * Look up the mod identifier data for an item.
     * Returns null if there is no mod specified mod identifier data, or it is part of a
     * custom itemstack definition {@link #registerCustomItemStack}
     *
     * Note: uniqueness and persistence is only guaranteed by mods using the game registry
     * correctly.
     *
     * @param item to lookup
     * @return a {@link UniqueIdentifier} for the item or null
     */
    public static UniqueIdentifier findUniqueIdentifierFor(Item item)
    {
        return GameData.getUniqueName(item);
    }



    /**
     * This will cause runtime injection of public static final fields to occur at various points
     * where mod blocks and items <em>could</em> be subject to change. This allows for dynamic
     * substitution to occur.
     *
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD})
    public @interface ObjectHolder {
        /**
         * If used on a class, this represents a modid only.
         * If used on a field, it represents a name, which can be abbreviated or complete.
         * Abbreviated names derive their modid from an enclosing ObjectHolder at the class level.
         *
         * @return either a modid or a name based on the rules above
         */
        String value();
    }
}
