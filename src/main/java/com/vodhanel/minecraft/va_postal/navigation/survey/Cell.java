package com.vodhanel.minecraft.va_postal.navigation.survey;

/**
 * What a block means to a walking postman, for the route survey. {@link SurveyGrid} maps each block to one of
 * these; the {@link Surveyor} only ever sees cells.
 * <ul>
 * <li>{@code passable}: the postman's body can be in it (feet or head).</li>
 * <li>{@code floor}: he can stand on top of it.</li>
 * <li>{@code cost}: how much a step costs when he's standing on it (roads are cheaper than grass); or, for a
 * passable cell he walks through, the extra cost of walking through it.</li>
 * </ul>
 */
public enum Cell {
    /** Air and anything a body walks through without noticing: flowers, torches, signs, carpets, grass. */
    OPEN(true, false, 0.0D),
    /** Ordinary ground: grass, dirt, stone, most full blocks. */
    GROUND(false, true, 1.0D),
    /** Roads and floors: paths, gravel, stone bricks, planks. Preferred. */
    ROAD(false, true, 0.7D),
    /** Slabs and stairs: a road a postman steps up onto without jumping. */
    STEP(false, true, 0.7D),
    /** Ground that's slow or unpleasant: sand, soul sand, snow, farmland. */
    ROUGH(false, true, 2.0D),
    /** Can't be walked through or stood on: fences, walls, leaves, panes, iron bars. */
    WALL(false, false, 0.0D),
    /** A door: passable once opened. */
    DOOR(true, false, 3.0D),
    /** A fence gate: passable once opened. */
    GATE(true, false, 3.0D),
    /** A ladder or vine: passable, climbable, and its top can be stood on. */
    LADDER(true, true, 2.0D),
    /** Crops: passable, but a postman shouldn't walk through someone's field. */
    CROP(true, false, 8.0D),
    /** Water: avoided (a postman doesn't swim with the mail). */
    WATER(false, false, 0.0D),
    /** Lava, fire, cactus, magma, berry bushes, powder snow: never. */
    DANGER(false, false, 0.0D),
    /** Not loaded or outside the survey area: treated as a wall. */
    UNKNOWN(false, false, 0.0D);

    public final boolean passable;
    public final boolean floor;
    public final double cost;

    Cell(boolean passable, boolean floor, double cost) {
        this.passable = passable;
        this.floor = floor;
        this.cost = cost;
    }

    /**
     * What a block is to a postman, by its name (and whether the server calls it solid). Names rather than
     * block tags, so it's testable without a server.
     */
    public static Cell of(String n, boolean solid) {
        switch (n) {
            case "AIR", "CAVE_AIR", "VOID_AIR":
                return OPEN;
            case "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS":
                return WATER;
            case "LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "SWEET_BERRY_BUSH", "POWDER_SNOW", "CAMPFIRE",
                 "SOUL_CAMPFIRE", "WITHER_ROSE", "POINTED_DRIPSTONE":
                return DANGER;
            case "IRON_DOOR", "IRON_BARS", "COBWEB", "BAMBOO", "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "SUGAR_CANE",
                 "CHAIN":
                return WALL; // chests: never route over a mailbox
            case "LADDER", "VINE", "SCAFFOLDING", "WEEPING_VINES", "WEEPING_VINES_PLANT", "TWISTING_VINES",
                 "TWISTING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT":
                return LADDER;
            case "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "MELON_STEM", "PUMPKIN_STEM", "ATTACHED_MELON_STEM",
                 "ATTACHED_PUMPKIN_STEM", "TORCHFLOWER_CROP", "PITCHER_CROP", "NETHER_WART":
                return CROP;
            case "DIRT_PATH", "GRAVEL":
                return ROAD;
            case "SAND", "RED_SAND", "SOUL_SAND", "SOUL_SOIL", "FARMLAND", "SNOW_BLOCK", "MUD", "CLAY", "HONEY_BLOCK",
                 "SLIME_BLOCK":
                return ROUGH;
            default:
                break;
        }
        if (n.endsWith("_DOOR")) {
            return DOOR;
        }
        if (n.endsWith("_FENCE_GATE")) {
            return GATE;
        }
        if (n.endsWith("_FENCE") || n.endsWith("_LEAVES") || n.endsWith("_TRAPDOOR") || n.endsWith("SHULKER_BOX")
                || n.endsWith("_PANE") || n.endsWith("_WALL") && !n.endsWith("_SIGN") || n.equals("COBBLESTONE_WALL")) {
            return WALL;
        }
        if (!solid) {
            return OPEN; // signs, torches, flowers, grass, rails, pressure plates, carpets
        }
        if (n.endsWith("_SLAB") || n.endsWith("_STAIRS")) {
            return STEP;
        }
        if (n.endsWith("_PLANKS") || n.contains("BRICK") || n.contains("COBBLESTONE") || n.startsWith("POLISHED_")
                || n.startsWith("SMOOTH_") || n.endsWith("_CONCRETE") || n.endsWith("_TILES") || n.startsWith("CUT_")
                || n.startsWith("QUARTZ")) {
            return ROAD;
        }
        return GROUND;
    }
}
