package com.example.backclip;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Жезл клиппинга.
 *
 * Как работает: блок из левой руки (любой, в т.ч. из других модов) превращается в ванильную сущность
 * block_display, которая ставится в клетку рядом с гранью, по которой вы кликнули, и «утапливается» вглубь
 * кликнутого блока. Часть декора, оказавшаяся внутри непрозрачного блока, не видна - получается клиппинг.
 *
 * ПКМ по блоку                 - поставить декор (блок в левой руке)
 * Shift + ПКМ по блоку         - убрать ближайший декор (блок вернётся в инвентарь)
 * ПКМ по воздуху               - изменить текущий параметр (глубина / поворот / наклон / размер)
 * Shift + ПКМ по воздуху       - переключить, какой параметр меняется
 */
public class ClipWandItem extends Item {
	/** Метка на сущностях, созданных жезлом. */
	public static final String TAG = "backclip";
	/** Метка с id блока (нужна, чтобы вернуть предмет при удалении). */
	public static final String TAG_BLOCK = "backclip_blk:";

	private static final int[] SCALES = {50, 75, 100, 125, 150, 200};
	private static final String[] MODES = {"Глубина", "Поворот", "Наклон", "Размер"};

	private static final Map<UUID, Settings> SETTINGS = new HashMap<>();

	private static final class Settings {
		int mode = 0;
		int depth = 6;   // 0..15, в 1/16 блока
		int yaw = 0;     // 0..23, шаг 15 градусов
		int tilt = 3;    // 0..6 -> (tilt-3)*15 градусов, то есть от -45 до +45
		int scale = 2;   // индекс в SCALES

		void advance() {
			switch (mode) {
				case 0 -> depth = (depth + 1) % 16;
				case 1 -> yaw = (yaw + 1) % 24;
				case 2 -> tilt = (tilt + 1) % 7;
				default -> scale = (scale + 1) % SCALES.length;
			}
		}

		String describe() {
			return "[" + MODES[mode] + "]  глубина " + depth + "/16  поворот " + (yaw * 15)
					+ "°  наклон " + ((tilt - 3) * 15) + "°  размер " + SCALES[scale] + "%";
		}
	}

	public ClipWandItem(Settings settings) {
		super(settings);
	}

	private static Settings settingsOf(PlayerEntity player) {
		return SETTINGS.computeIfAbsent(player.getUuid(), id -> new Settings());
	}

	// ---------- ПКМ по воздуху: настройки ----------

	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		if (world.isClient()) return ActionResult.SUCCESS;
		Settings s = settingsOf(user);
		if (user.isSneaking()) {
			s.mode = (s.mode + 1) % MODES.length;
		} else {
			s.advance();
		}
		user.sendMessage(Text.literal(s.describe()), true);
		return ActionResult.SUCCESS;
	}

	// ---------- ПКМ по блоку: поставить / убрать ----------

	@Override
	public ActionResult useOnBlock(ItemUsageContext ctx) {
		World world = ctx.getWorld();
		PlayerEntity player = ctx.getPlayer();
		if (player == null) return ActionResult.PASS;
		if (world.isClient()) return ActionResult.SUCCESS;

		BlockPos pos = ctx.getBlockPos();
		Direction face = ctx.getSide();

		if (player.isSneaking()) {
			return remove(world, player, pos, face, ctx.getHitPos());
		}

		ItemStack offHand = player.getOffHandStack();
		if (!(offHand.getItem() instanceof BlockItem blockItem)) {
			player.sendMessage(Text.literal("Положите в левую руку блок, который нужно вставить"), true);
			return ActionResult.FAIL;
		}

		MinecraftServer server = world.getServer();
		if (server == null || !(world instanceof ServerWorld serverWorld)) return ActionResult.PASS;

		Block block = blockItem.getBlock();
		BlockState decor = block.getDefaultState();
		Settings s = settingsOf(player);

		// Клетка рядом с гранью (там будет «стоять» декор) и сдвиг вглубь кликнутого блока.
		BlockPos cell = pos.offset(face);
		float sc = SCALES[s.scale] / 100f;
		float d = s.depth / 16f;

		Quaternionf q = new Quaternionf().rotationYXZ(
				(float) Math.toRadians(s.yaw * 15f),
				(float) Math.toRadians((s.tilt - 3) * 15f),
				0f);

		// Поворот и масштаб - вокруг центра блока; масштаб дополнительно сдвигаем так,
		// чтобы грань, прилегающая к стене/полу, осталась на месте.
		Vector3f center = new Vector3f(0.5f, 0.5f, 0.5f);
		Vector3f rotated = new Vector3f(center).mul(sc);
		q.transform(rotated);
		Vector3f t = new Vector3f(center).sub(rotated);
		float along = 0.5f * (sc - 1f) - d; // вдоль нормали грани (минус = вглубь блока)
		t.add(face.getOffsetX() * along, face.getOffsetY() * along, face.getOffsetZ() * along);

		String blockId = Registries.BLOCK.getId(block).toString();
		String cmd = String.format(Locale.ROOT,
				"summon minecraft:block_display %f %f %f {block_state:%s,Tags:[\"%s\",\"%s%s\"],"
						+ "transformation:{left_rotation:[%ff,%ff,%ff,%ff],right_rotation:[0f,0f,0f,1f],"
						+ "translation:[%ff,%ff,%ff],scale:[%ff,%ff,%ff]}}",
				(double) cell.getX(), (double) cell.getY(), (double) cell.getZ(),
				blockStateNbt(decor), TAG, TAG_BLOCK, blockId,
				q.x, q.y, q.z, q.w,
				t.x, t.y, t.z,
				sc, sc, sc);

		ServerCommandSource source = server.getCommandSource().withWorld(serverWorld).withSilent();
		server.getCommandManager().executeWithPrefix(source, cmd);

		if (!player.isCreative()) offHand.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.8f, 0.9f);
		return ActionResult.SUCCESS;
	}

	private ActionResult remove(World world, PlayerEntity player, BlockPos pos, Direction face, Vec3d hit) {
		Box area = new Box(pos).union(new Box(pos.offset(face))).expand(1.0);
		List<Entity> found = world.getEntitiesByClass(Entity.class, area,
				e -> e.getType() == EntityType.BLOCK_DISPLAY && e.getCommandTags().contains(TAG));
		if (found.isEmpty()) return ActionResult.PASS;

		Entity nearest = null;
		double best = Double.MAX_VALUE;
		for (Entity e : found) {
			double dist = e.squaredDistanceTo(hit.x - 0.5, hit.y - 0.5, hit.z - 0.5);
			if (dist < best) {
				best = dist;
				nearest = e;
			}
		}
		if (nearest == null) return ActionResult.PASS;

		for (String tag : nearest.getCommandTags()) {
			if (tag.startsWith(TAG_BLOCK)) {
				Identifier id = Identifier.tryParse(tag.substring(TAG_BLOCK.length()));
				if (id != null) {
					Block block = Registries.BLOCK.get(id);
					if (block.asItem() != net.minecraft.item.Items.AIR && !player.isCreative()) {
						player.getInventory().offerOrDrop(new ItemStack(block));
					}
				}
				break;
			}
		}
		nearest.discard();
		world.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.8f, 1.2f);
		return ActionResult.SUCCESS;
	}

	/** {Name:"mod:block",Properties:{facing:"north",...}} - формат block_state для block_display. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String blockStateNbt(BlockState state) {
		StringBuilder sb = new StringBuilder("{Name:\"")
				.append(Registries.BLOCK.getId(state.getBlock())).append('"');
		if (!state.getEntries().isEmpty()) {
			sb.append(",Properties:{");
			boolean first = true;
			for (Map.Entry<Property<?>, Comparable<?>> e : state.getEntries().entrySet()) {
				Property p = e.getKey();
				if (!first) sb.append(',');
				first = false;
				sb.append(p.getName()).append(":\"").append(p.name((Comparable) e.getValue())).append('"');
			}
			sb.append('}');
		}
		return sb.append('}').toString();
	}
}
