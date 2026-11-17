package pl.gradient;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class GradientCommand {

    private static final String USAGE =
        "Uzycie: //gradient <maska> <os> <bloki> [opcje]\n"
        + " maska: * (wszystko oprocz powietrza), all (wszystko), albo bloki po przecinku: stone,dirt\n"
        + " os: x | y | z (kierunek gradientu, od mniejszej do wiekszej wspolrzednej)\n"
        + " bloki: lista po przecinku, waga przez *, np. stone*3,andesite,gravel\n"
        + " opcje: rev  from=N  to=N  dither=0..1  repeat=N  seed=N  strict\n"
        + "Przyklad: //gradient stone y stone,andesite,gravel,dirt from=60 to=120 dither=0.2";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : new String[] {"/gradient", "/grad"}) {
            dispatcher.register(Commands.literal(name)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(USAGE), false);
                    return 1;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .executes(GradientCommand::run)));
        }
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal(msg));
        return 0;
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer sp = src.getPlayerOrException();
        String raw = StringArgumentType.getString(ctx, "args").trim();
        String[] t = raw.split("\\s+");
        if (t.length < 3) {
            return fail(src, USAGE);
        }

        Predicate<BlockType> mask;
        String maskStr = t[0].toLowerCase();
        if (maskStr.equals("*")) {
            mask = type -> !type.getMaterial().isAir();
        } else if (maskStr.equals("all")) {
            mask = type -> true;
        } else {
            List<BlockType> allowed = new ArrayList<>();
            for (String s : maskStr.split(",")) {
                BlockType bt = BlockTypes.get(s);
                if (bt == null) return fail(src, "Nieznany blok w masce: " + s);
                allowed.add(bt);
            }
            mask = allowed::contains;
        }

        String axisStr = t[1].toLowerCase();
        int axis;
        switch (axisStr) {
            case "x" -> axis = 0;
            case "y" -> axis = 1;
            case "z" -> axis = 2;
            default -> { return fail(src, "Os musi byc x, y albo z, a nie: " + axisStr); }
        }

        List<BlockType> palette = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (String s : t[2].split(",")) {
            String id = s;
            double w = 1;
            int star = s.indexOf('*');
            if (star >= 0) {
                id = s.substring(0, star);
                try {
                    w = Double.parseDouble(s.substring(star + 1));
                } catch (NumberFormatException e) {
                    return fail(src, "Zla waga: " + s);
                }
                if (w <= 0) return fail(src, "Waga musi byc wieksza od 0: " + s);
            }
            BlockType bt = BlockTypes.get(id);
            if (bt == null) return fail(src, "Nieznany blok: " + id);
            palette.add(bt);
            weights.add(w);
        }
        double[] cum = new double[palette.size()];
        double total = 0;
        for (int i = 0; i < cum.length; i++) {
            total += weights.get(i);
            cum[i] = total;
        }

        boolean rev = false, strict = false;
        Integer fromOpt = null, toOpt = null;
        double dither = 0;
        int repeat = 1;
        long seed = 0;
        try {
            for (int i = 3; i < t.length; i++) {
                String o = t[i].toLowerCase();
                if (o.equals("rev")) rev = true;
                else if (o.equals("strict")) strict = true;
                else if (o.startsWith("from=")) fromOpt = Integer.parseInt(o.substring(5));
                else if (o.startsWith("to=")) toOpt = Integer.parseInt(o.substring(3));
                else if (o.startsWith("dither=")) dither = Double.parseDouble(o.substring(7));
                else if (o.startsWith("repeat=")) repeat = Integer.parseInt(o.substring(7));
                else if (o.startsWith("seed=")) seed = Long.parseLong(o.substring(5));
                else return fail(src, "Nieznana opcja: " + o);
            }
        } catch (NumberFormatException e) {
            return fail(src, "Zla liczba w opcjach.");
        }
        if (repeat < 1) return fail(src, "repeat musi byc >= 1");

        Player player = FabricAdapter.adaptPlayer(sp);
        if (!player.hasPermission("worldedit.region.replace")) {
            return fail(src, "Brak uprawnien (worldedit.region.replace).");
        }
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(player);
        World world = player.getWorld();
        Region region;
        try {
            region = session.getSelection(world);
        } catch (IncompleteRegionException e) {
            return fail(src, "Najpierw zaznacz obszar (pos1 i pos2).");
        }

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int regionMin = axis == 0 ? min.x() : axis == 1 ? min.y() : min.z();
        int regionMax = axis == 0 ? max.x() : axis == 1 ? max.y() : max.z();
        int from = fromOpt != null ? fromOpt : regionMin;
        int to = toOpt != null ? toOpt : regionMax;
        if (from > to) return fail(src, "from nie moze byc wieksze niz to.");

        int changed = 0;
        boolean limitHit = false;
        double span = (double) (to - from + 1);

        try (EditSession es = session.createEditSession(player)) {
            try {
                for (BlockVector3 p : region) {
                    int c = axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
                    if (strict && (c < from || c > to)) continue;

                    BlockType current = es.getBlock(p).getBlockType();
                    if (!mask.test(current)) continue;

                    double v = (c - from + 0.5) / span;
                    v = Math.max(0, Math.min(1, v));
                    if (rev) v = 1 - v;
                    if (repeat > 1) {
                        v *= repeat;
                        v -= Math.floor(v);
                    }
                    if (dither > 0) {
                        v += (noise(seed, p.x(), p.y(), p.z()) - 0.5) * dither;
                    }
                    v = Math.max(0, Math.min(0.999999, v));

                    double target = v * total;
                    int idx = 0;
                    while (idx < cum.length - 1 && target >= cum[idx]) idx++;
                    BlockType result = palette.get(idx);

                    if (!result.equals(current)) {
                        es.setBlock(p, result.getDefaultState());
                        changed++;
                    }
                }
            } catch (MaxChangedBlocksException e) {
                limitHit = true;
            }
            session.remember(es);
        }

        final int fChanged = changed;
        final boolean fLimit = limitHit;
        src.sendSuccess(() -> Component.literal(
            "Gradient: zmieniono " + fChanged + " blokow."
                + (fLimit ? " Przerwano na limicie zmian WorldEdit (//limit)." : "")
                + " Cofniesz przez //undo."), false);
        return 1;
    }

    private static double noise(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
        h ^= y * 0x94D049BB133111EBL;
        h = Long.rotateLeft(h, 31) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
