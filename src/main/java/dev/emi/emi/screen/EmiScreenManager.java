package dev.emi.emi.screen;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.glfw.GLFW;

import dev.emi.emi.EmiClient;
import dev.emi.emi.EmiConfig;
import dev.emi.emi.EmiDragDropHandlers;
import dev.emi.emi.EmiExclusionAreas;
import dev.emi.emi.EmiFavorite;
import dev.emi.emi.EmiFavorites;
import dev.emi.emi.EmiHistory;
import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiLog;
import dev.emi.emi.EmiMain;
import dev.emi.emi.EmiReloadManager;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiStackProviders;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiFillAction;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.bind.EmiBind;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.mixin.accessor.ScreenAccessor;
import dev.emi.emi.screen.widget.EmiSearchWidget;
import dev.emi.emi.screen.widget.SizedButtonWidget;
import dev.emi.emi.search.EmiSearch;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

public class EmiScreenManager {
	private static final int PADDING_SIZE = 1;
	private static final int ENTRY_SIZE = 16 + PADDING_SIZE * 2;
	private static final StackBatcher searchBatcher = new StackBatcher();
	private static final StackBatcher favoriteBatcher = new StackBatcher();
	private static MinecraftClient client = MinecraftClient.getInstance();
	private static List<? extends EmiIngredient> stacks;
	private static int left, right;
	private static int lastWidth, lastHeight;
	private static List<Bounds> lastExclusion;
	private static ScreenSpace searchSpace;
	private static ScreenSpace favoriteSpace;
	public static EmiPlayerInventory lastPlayerInventory;
	public static int searchPage, favoritePage;
	public static int lastMouseX, lastMouseY;
	// The stack that was clicked on, for determining when a drag properly starts
	private static EmiIngredient pressedStack = EmiStack.EMPTY;
	private static EmiIngredient draggedStack = EmiStack.EMPTY;

	public static EmiSearchWidget search = new EmiSearchWidget(client.textRenderer, 0, 0, 160, 18);
	public static SizedButtonWidget emi = new SizedButtonWidget(0, 0, 20, 20, 204, 64,
		() -> true, (w) -> client.setScreen(new ConfigScreen(client.currentScreen)),
		List.of(EmiPort.translatable("tooltip.emi.config", EmiRenderHelper.getEmiText())));
	public static SizedButtonWidget tree = new SizedButtonWidget(0, 0, 20, 20, 184, 64,
		() -> true, (w) -> EmiApi.viewRecipeTree(),
		List.of(EmiPort.translatable("tooltip.emi.recipe_tree")));
	public static SizedButtonWidget craftableButton = new SizedButtonWidget(0, 0, 20, 20, 164, 64, () -> true, (w) -> {
		EmiConfig.craftable = !EmiConfig.craftable;
		EmiConfig.writeConfig();
		EmiSearch.update();
		lastPlayerInventory = null;
		recalculate();
	}, () -> EmiConfig.craftable ? 60 : 0,
		() -> List.of(EmiConfig.craftable ? EmiPort.translatable("tooltip.emi.craftable_toggle_craftable")
			: EmiPort.translatable("tooltip.emi.craftable_toggle_index")));
	public static SizedButtonWidget localCraftables = new SizedButtonWidget(0, 0, 20, 20, 144, 64, () -> EmiConfig.craftable, (w) -> {
		EmiConfig.localCraftable = !EmiConfig.localCraftable;
		EmiConfig.writeConfig();
		lastPlayerInventory = null;
		recalculate();
	}, () -> EmiConfig.localCraftable ? 60 : 0,
	() -> List.of(EmiConfig.localCraftable ? EmiPort.translatable("tooltip.emi.local_craftable_toggle_on")
		: EmiPort.translatable("tooltip.emi.local_craftable_toggle_off")));
	public static SizedButtonWidget searchLeft = new SizedButtonWidget(0, 0, 16, 16, 224, 64,
		EmiScreenManager::hasMultipleSearchPages, (w) -> scrollSearch(-1));
	public static SizedButtonWidget searchRight = new SizedButtonWidget(0, 0, 16, 16, 240, 64,
		EmiScreenManager::hasMultipleSearchPages, (w) -> scrollSearch(1));
	public static SizedButtonWidget favoriteLeft = new SizedButtonWidget(0, 0, 16, 16, 224, 64,
		EmiScreenManager::hasMultipleFavoritePages, (w) -> scrollFavorite(-1));
	public static SizedButtonWidget favoriteRight = new SizedButtonWidget(0, 0, 16, 16, 240, 64,
		EmiScreenManager::hasMultipleFavoritePages, (w) -> scrollFavorite(1));

	private static boolean hasMultipleSearchPages() {
		return searchSpace.pageSize < stacks.size();
	}

	private static boolean hasMultipleFavoritePages() {
		return favoriteSpace.pageSize < EmiFavorites.favorites.size();
	}

	private static boolean isDisabled() {
		return EmiReloadManager.isReloading() || !EmiConfig.enabled;
	}

	public static void recalculate() {
		if (EmiConfig.craftable) {
			EmiPlayerInventory inv = new EmiPlayerInventory(client.player);
			if (!inv.isEqual(lastPlayerInventory)) {
				lastPlayerInventory = inv;
				EmiSearch.update();
			}
		}
		if (stacks != EmiSearch.stacks) {
			searchBatcher.repopulate();
		}
		stacks = EmiSearch.stacks;

		Screen screen = client.currentScreen;
		List<Bounds> exclusion = EmiExclusionAreas.getExclusion(screen);
		if (lastWidth == screen.width && lastHeight == screen.height && exclusion.size() == lastExclusion.size()) {
			boolean same = true;
			for (int i = 0; i < exclusion.size(); i++) {
				Bounds a = exclusion.get(i);
				Bounds b = lastExclusion.get(i);
				if (a.x() != b.x() || a.y() != b.y() || a.width() != b.width() || a.height() != b.height()) {
					same = false;
					break;
				}
			}
			if (same) {
				return;
			}
		}
		searchBatcher.repopulate();
		favoriteBatcher.repopulate();
		lastWidth = screen.width;
		lastHeight = screen.height;
		lastExclusion = exclusion;
		if (screen instanceof EmiScreen emi) {
			left = Math.max(ENTRY_SIZE * 2, emi.emi$getLeft());
			right = Math.min(screen.width - ENTRY_SIZE * 2, emi.emi$getRight());
			int xMin = right;
			int xMax = screen.width - 1;
			int yMin = 18;
			int yMax = screen.height - 22;
			int tx = xMin + (xMax - xMin) % ENTRY_SIZE / 2;
			int ty = yMin;
			int tw = (xMax - xMin) / ENTRY_SIZE;
			int th = (yMax - yMin) / ENTRY_SIZE;
			searchSpace = new ScreenSpace(xMin, xMax, yMin, yMax, tx, ty, tw, th, true, exclusion);
			int fxMin = 1;
			int fxMax = left;
			int fyMin = 18;
			int fyMax = screen.height - 22;
			int ftx = fxMin + (fxMax - fxMin) % ENTRY_SIZE / 2;
			int fty = fyMin;
			int ftw = (fxMax - fxMin) / ENTRY_SIZE;
			int fth = (fyMax - fyMin) / ENTRY_SIZE;
			favoriteSpace = new ScreenSpace(fxMin, fxMax, fyMin, fyMax, ftx, fty, ftw, fth, false, exclusion);

			searchLeft.x = searchSpace.tx;
			searchLeft.y = 2;
			searchRight.x = searchSpace.tx + searchSpace.tw * ENTRY_SIZE - 16;
			searchRight.y = 2;
	
			favoriteLeft.x = favoriteSpace.tx;
			favoriteLeft.y = 2;
			favoriteRight.x = favoriteSpace.tx + favoriteSpace.tw * ENTRY_SIZE - 16;
			favoriteRight.y = 2;
		}
	}

	public static EmiStackInteraction getHoveredStack(int mouseX, int mouseY, boolean notClick) {
		EmiStackInteraction stack = EmiStackProviders.getStackAt(client.currentScreen, mouseX, mouseY, notClick);
		if (!stack.isEmpty()) {
			return stack;
		}
		if (searchSpace.pageSize > 0 && searchSpace.contains(mouseX, mouseY)
				&& mouseX >= searchSpace.tx && mouseY >= searchSpace.ty) {
			int x = (mouseX - searchSpace.tx) / ENTRY_SIZE;
			int y = (mouseY - searchSpace.ty) / ENTRY_SIZE;
			int n = searchSpace.getRawOffset(x, y);
			if (n != -1 && (n = n + searchSpace.pageSize * searchPage) < stacks.size()) {
				return of(stacks.get(n));
			}
		}
		if (favoriteSpace.pageSize > 0 && favoriteSpace.contains(mouseX, mouseY)
				&& mouseX >= favoriteSpace.tx && mouseY >= favoriteSpace.ty) {
			int x = (mouseX - favoriteSpace.tx) / ENTRY_SIZE;
			int y = (mouseY - favoriteSpace.ty) / ENTRY_SIZE;
			int n = favoriteSpace.getRawOffset(x, y);
			if (n != -1 && (n = n + favoriteSpace.pageSize * favoritePage) < EmiFavorites.favorites.size()) {
				return of(EmiFavorites.favorites.get(n));
			}
		}
		return EmiStackInteraction.EMPTY;
	}

	private static EmiStackInteraction of(EmiIngredient stack) {
		if (stack instanceof EmiFavorite fav) {
			return new EmiStackInteraction(stack, fav.getRecipe(), true);
		}
		return new EmiStackInteraction(stack);
	}
	
	public static void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		client.getProfiler().push("emi");
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		Screen screen = client.currentScreen;
		boolean visible = !isDisabled();
		emi.visible = visible;
		tree.visible = visible;
		craftableButton.visible = visible;
		localCraftables.visible = visible;
		searchLeft.visible = visible;
		searchRight.visible = visible;
		favoriteLeft.visible = visible;
		favoriteRight.visible = visible;
		if (isDisabled()) {
			if (EmiReloadManager.isReloading()) {
				client.textRenderer.drawWithShadow(matrices, "EMI Reloading...", 48, screen.height - 16, -1);
			}
			client.getProfiler().pop();
			return;
		}
		client.getProfiler().push("sidebars");
		if (screen instanceof EmiScreen emi) {
			client.getProfiler().push("prep");
			EmiScreenManager.search.setZOffset(0);
			EmiScreenManager.search.render(matrices, mouseX, mouseY, delta);
			recalculate();
	
			client.getProfiler().swap("search");
			int searchPageSize = searchSpace.pageSize;
			if (searchPageSize > 0) {
				int totalSearchPages = (stacks.size() - 1) / searchPageSize + 1;
				if (searchPage >= totalSearchPages) {
					searchPage = 0;
					searchBatcher.repopulate();
				} else if (searchPage < 0) {
					searchPage = totalSearchPages - 1;
					searchBatcher.repopulate();
				}
				// Do not ask for whom the offsets toll, for it is you
				searchBatcher.begin(searchSpace.tx + PADDING_SIZE - 2, searchSpace.ty + PADDING_SIZE - 3, 0);
				DrawableHelper.drawCenteredText(matrices, client.textRenderer, EmiPort.translatable("emi.page", searchPage + 1, totalSearchPages),
					searchSpace.xMin + (searchSpace.xMax - searchSpace.xMin) / 2, 5, 0xFFFFFF);
				int i = searchPageSize * searchPage;
				outer:
				for (int yo = 0; yo < searchSpace.th; yo++) {
					for (int xo = 0; xo < searchSpace.getWidth(yo); xo++) {
						if (i >= stacks.size()) {
							break outer;
						}
						int cx = searchSpace.getX(xo, yo);
						int cy = searchSpace.getY(xo, yo);
						EmiIngredient stack = stacks.get(i++);
						searchBatcher.render(stack, matrices, cx + 1, cy + 1, delta);
						if (EmiConfig.highlightDefaulted) {
							if (BoM.getRecipe(stack) != null) {
								RenderSystem.enableDepthTest();
								DrawableHelper.fill(matrices, cx, cy, cx + ENTRY_SIZE, cy + ENTRY_SIZE, 0x3300ff00);
							}
						}
					}
				}
				searchBatcher.draw();
			}

			client.getProfiler().swap("favorite");
			int favoritePageSize = favoriteSpace.pageSize;
			if (favoritePageSize > 0) {
				int totalFavoritePages = (EmiFavorites.favorites.size() - 1) / favoritePageSize + 1;
				if (favoritePage >= totalFavoritePages) {
					favoritePage = 0;
					favoriteBatcher.repopulate();
				} else if (favoritePage < 0) {
					favoritePage = totalFavoritePages - 1;
					favoriteBatcher.repopulate();
				}
				favoriteBatcher.begin(favoriteSpace.tx + PADDING_SIZE - 2, favoriteSpace.ty + PADDING_SIZE - 3, 0);
				DrawableHelper.drawCenteredText(matrices, client.textRenderer,
					EmiPort.translatable("emi.page", favoritePage + 1, totalFavoritePages),
					favoriteSpace.xMin + (favoriteSpace.xMax - favoriteSpace.xMin) / 2, 5, 0xFFFFFF);
				int i = favoritePageSize * favoritePage;
				outer:
				for (int yo = 0; yo < favoriteSpace.th; yo++) {
					for (int xo = 0; xo < favoriteSpace.getWidth(yo); xo++) {
						if (i >= EmiFavorites.favorites.size()) {
							break outer;
						}
						int cx = favoriteSpace.getX(xo, yo);
						int cy = favoriteSpace.getY(xo, yo);
						favoriteBatcher.render(EmiFavorites.favorites.get(i++), matrices, cx + 1, cy + 1, delta);
					}
				}
				favoriteBatcher.draw();
			}
			if (!draggedStack.isEmpty()) {
				if (favoriteSpace.contains(mouseX, mouseY)) {
					int index = favoriteSpace.getClosestEdge(mouseX, mouseY);
					if (index + favoriteSpace.pageSize * favoritePage > EmiFavorites.favorites.size()) {
						index = EmiFavorites.favorites.size() - favoriteSpace.pageSize * favoritePage;	
					}
					int dx = favoriteSpace.getEdgeX(index);
					int dy = favoriteSpace.getEdgeY(index);
					DrawableHelper.fill(matrices, dx - 1, dy, dx + 1, dy + 18, 0xFF00FFFF);
				}
				MatrixStack view = RenderSystem.getModelViewStack();
				view.push();
				view.translate(0, 0, 200);
				RenderSystem.applyModelViewMatrix();
				draggedStack.render(matrices, mouseX - 8, mouseY - 8, delta, EmiIngredient.RENDER_ICON);
				view.pop();
				RenderSystem.applyModelViewMatrix();
			} else {
				client.getProfiler().swap("hover");
				MatrixStack view = RenderSystem.getModelViewStack();
				view.push();
				view.translate(0, 0, 200);
				RenderSystem.applyModelViewMatrix();
				EmiIngredient hov = getHoveredStack(mouseX, mouseY, false).getStack();
				if (mouseX >= searchSpace.xMin) {
					EmiClient.shiftTooltipsLeft = true;
				}
				((ScreenAccessor) screen).invokeRenderTooltipFromComponents(matrices, hov.getTooltip(), mouseX, mouseY);
				EmiClient.shiftTooltipsLeft = false;
				view.pop();
				RenderSystem.applyModelViewMatrix();
				client.getProfiler().pop();
			}
		}
		if (EmiConfig.devMode) {
			client.getProfiler().swap("dev");
			int color = 0xFFFFFF;
			String title = "EMI Dev Mode";
			int off = -16;
			if (EmiLog.WARNINGS.size() > 0) {
				color = 0xFF0000;
				off = -11;
				String warnCount = EmiLog.WARNINGS.size() + " Warnings";
				client.textRenderer.drawWithShadow(matrices, warnCount, 48, screen.height - 21, color);
				int width = Math.max(client.textRenderer.getWidth(title), client.textRenderer.getWidth(warnCount));
				if (mouseX >= 48 && mouseX < width + 48 && mouseY > screen.height - 28) {
					screen.renderTooltip(matrices, Stream.concat(Stream.of("See log for more information"),
						EmiLog.WARNINGS.stream()).map(s -> {
							String a = s;
							if (a.length() > 10 && client.textRenderer.getWidth(a) > screen.width - 20) {
								a = client.textRenderer.trimToWidth(a, screen.width - 30) + "...";
							}
							return EmiPort.literal(a);
						})
						.collect(Collectors.toList()), 0, 20);
				}
			}
			client.textRenderer.drawWithShadow(matrices, title, 48, screen.height + off, color);
		}
		client.getProfiler().pop();
		client.getProfiler().pop();
	}

	public static void addWidgets(Screen screen) {
		// force recalculation
		lastWidth = -1;
		lastPlayerInventory = null;
		recalculate();
		if (EmiConfig.centerSearchBar) {
			search.x = (screen.width - 160) / 2;
			search.y = screen.height - 22;
			search.setWidth(160);
		} else {
			search.x = searchSpace.tx;
			search.y = screen.height - 21;
			search.setWidth(searchSpace.xMax - searchSpace.xMin - 54);
		}
		search.setTextFieldFocused(false);

		emi.x = 2;
		emi.y = screen.height - 22;

		tree.x = 24;
		tree.y = screen.height - 22;

		craftableButton.x = screen.width - 22;
		craftableButton.y = screen.height - 22;

		localCraftables.x = screen.width - 44;
		localCraftables.y = screen.height - 22;

		searchLeft.x = searchSpace.tx;
		searchLeft.y = 2;
		searchRight.x = searchSpace.tx + searchSpace.tw * ENTRY_SIZE - 16;
		searchRight.y = 2;

		favoriteLeft.x = favoriteSpace.tx;
		favoriteLeft.y = 2;
		favoriteRight.x = favoriteSpace.tx + favoriteSpace.tw * ENTRY_SIZE - 16;
		favoriteRight.y = 2;

		screen.addDrawableChild(search);
		screen.addDrawableChild(emi);
		screen.addDrawableChild(tree);
		screen.addDrawableChild(craftableButton);
		screen.addDrawableChild(localCraftables);
		screen.addDrawableChild(searchLeft);
		screen.addDrawableChild(searchRight);
		screen.addDrawableChild(favoriteLeft);
		screen.addDrawableChild(favoriteRight);
	}

	public static void scrollSearch(int delta) {
		if (searchSpace.pageSize == 0) {
			return;
		}
		searchPage += delta;
		int pageSize = searchSpace.pageSize;
		int totalPages = (stacks.size() - 1) / pageSize + 1;
		if (totalPages <= 1) {
			return;
		}
		if (searchPage >= totalPages) {
			searchPage = 0;
		} else if (searchPage < 0) {
			searchPage = totalPages - 1;
		}
		searchBatcher.repopulate();
	}

	public static void scrollFavorite(int delta) {
		if (favoriteSpace.pageSize == 0) {
			return;
		}
		favoritePage += delta;
		int pageSize = favoriteSpace.pageSize;
		int totalPages = (EmiFavorites.favorites.size() - 1) / pageSize + 1;
		if (totalPages <= 1) {
			return;
		}
		if (favoritePage >= totalPages) {
			favoritePage = 0;
		} else if (favoritePage < 0) {
			favoritePage = totalPages - 1;
		}
		favoriteBatcher.repopulate();
	}

	public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (isDisabled()) {
			return false;
		}
		recalculate();
		if (searchSpace.contains((int) mouseX, (int) mouseY)) {
			scrollSearch((int) -amount);
			return true;
		} else if (favoriteSpace.contains((int) mouseX, (int) mouseY)) {
			scrollFavorite((int) -amount);
			return true;
		}
		return false;
	}

	public static boolean mouseClicked(double mouseX, double mouseY, int button) {
		// TODO This makes sure focus always goes away, but might double fire, is that a problem?
		EmiScreenManager.search.mouseClicked(mouseX, mouseY, button);
		if (isDisabled()) {
			if (EmiConfig.toggleVisibility.matchesMouse(button)) {
				EmiConfig.enabled = !EmiConfig.enabled;
				EmiConfig.writeConfig();
				return true;
			}
			return false;
		}
		recalculate();
		EmiIngredient ingredient = getHoveredStack((int) mouseX, (int) mouseY, false).getStack();
		pressedStack = ingredient;
		if (!ingredient.isEmpty()) {
			return true;
		}
		return false;
	}

	public static boolean mouseReleased(double mouseX, double mouseY, int button) {
		try {
			if (isDisabled()) {
				return false;
			}
			recalculate();
			if (EmiConfig.cheatMode) {
				if (client.currentScreen instanceof HandledScreen<?> handled) {
					ItemStack cursor = handled.getScreenHandler().getCursorStack();
					if (!cursor.isEmpty() && searchSpace.contains(lastMouseX, lastMouseY)) {
						handled.getScreenHandler().setCursorStack(ItemStack.EMPTY);
						ClientPlayNetworking.send(EmiMain.DESTROY_HELD, new PacketByteBuf(Unpooled.buffer()));
						// Returning false here makes the handled screen do something and removes a bug, oh well.
						return false;
					}
				}
			}
			if (!pressedStack.isEmpty()) {
				if (!draggedStack.isEmpty()) {
					int mx = (int) mouseX;
					int my = (int) mouseY;
					if (favoriteSpace.contains(mx, my)) {
						int index = favoriteSpace.getClosestEdge(mx, my);
						EmiFavorites.addFavoriteAt(draggedStack, index + favoriteSpace.pageSize * favoritePage);
						favoriteBatcher.repopulate();
						return true;
					} else {
						if (EmiDragDropHandlers.dropStack(client.currentScreen, draggedStack, mx, my)) {
							return true;
						}
					}
				} else {
					EmiStackInteraction hovered = getHoveredStack((int) mouseX, (int) mouseY, false);
					if (draggedStack.isEmpty() && stackInteraction(hovered, bind -> bind.matchesMouse(button))) {
						return true;
					}
				}
				if (genericInteraction(bind -> bind.matchesMouse(button))) {
					return true;
				}
			}
			return false;
		} finally {
			pressedStack = EmiStack.EMPTY;
			draggedStack = EmiStack.EMPTY;
		}
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (isDisabled()) {
			return false;
		}
		if (draggedStack.isEmpty()) {
			if (client.currentScreen instanceof HandledScreen<?> handled) {
				if (!handled.getScreenHandler().getCursorStack().isEmpty()) {
					return false;
				}
			}
			recalculate();
			EmiStackInteraction hovered = getHoveredStack((int) mouseX, (int) mouseY, false);
			if (hovered.getStack() != pressedStack) {
				draggedStack = pressedStack;
			}
		}
		return false;
	}

	public static boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (isDisabled()) {
			if (EmiConfig.toggleVisibility.matchesKey(keyCode, scanCode)) {
				EmiConfig.enabled = !EmiConfig.enabled;
				EmiConfig.writeConfig();
				return true;
			}
			return false;
		}
		if (EmiScreenManager.search.keyPressed(keyCode, scanCode, modifiers) || EmiScreenManager.search.isActive()) {
			return true;
		} else if (EmiUtil.isControlDown() && keyCode == GLFW.GLFW_KEY_Y) {
			EmiApi.displayAllRecipes();
			return true;
		} else {
			recalculate();
			if (stackInteraction(getHoveredStack(lastMouseX, lastMouseY, true), bind -> bind.matchesKey(keyCode, scanCode))) {
				return true;
			}
			if (genericInteraction(bind -> bind.matchesKey(keyCode, scanCode))) {
				return true;
			}
		}
		return false;
	}

	public static boolean genericInteraction(Function<EmiBind, Boolean> function) {
		if (function.apply(EmiConfig.toggleVisibility)) {
			EmiConfig.enabled = !EmiConfig.enabled;
			EmiConfig.writeConfig();
			return true;
		} else if (function.apply(EmiConfig.focusSearch)) {
			if (client.currentScreen != null) {
				client.currentScreen.setFocused(search);
				search.setTextFieldFocused(true);
				return true;
			}
		} else if (function.apply(EmiConfig.viewTree)) {
			EmiApi.viewRecipeTree();
			return true;
		} else if (function.apply(EmiConfig.back)) {
			EmiHistory.pop();
			return true;
		} else if (function.apply(EmiConfig.toggleCraftable)) {
			EmiConfig.craftable = !EmiConfig.craftable;
			EmiConfig.writeConfig();
			EmiSearch.update();
			lastPlayerInventory = null;
			recalculate();
			return true;
		} else if (function.apply(EmiConfig.toggleLocalCraftable) && EmiConfig.craftable) {
			EmiConfig.localCraftable = !EmiConfig.localCraftable;
			EmiConfig.writeConfig();
			lastPlayerInventory = null;
			recalculate();
			return true;
		}
		return false;
	}

	public static boolean stackInteraction(EmiStackInteraction stack, Function<EmiBind, Boolean> function) {
		EmiIngredient ingredient = stack.getStack();
		if (!ingredient.isEmpty()) {
			if (ingredient instanceof EmiFavorite fav && fav.getRecipe() != null) {
				EmiFillAction action = null;
				boolean all = false;
				if (function.apply(EmiConfig.craftAllToInventory)) {
					action = EmiFillAction.QUICK_MOVE;
					all = true;
				} else if (function.apply(EmiConfig.craftOneToInventory)) {
					action = EmiFillAction.QUICK_MOVE;
				} else if (function.apply(EmiConfig.craftOneToCursor)) {
					action = EmiFillAction.CURSOR;
				} else if (function.apply(EmiConfig.craftAll)) {
					action = EmiFillAction.FILL;
					all = true;
				} else if (function.apply(EmiConfig.craftOne)) {
					action = EmiFillAction.FILL;
				}
				if (action != null) {
					if (EmiApi.performFill(fav.getRecipe(), action, all ? Integer.MAX_VALUE : 1)) {
						return true;
					}
				}
			}
			if (EmiConfig.cheatMode) {
				if (ingredient.getEmiStacks().size() == 1) {
					if (function.apply(EmiConfig.cheatOneToInventory)) {
						return give(ingredient.getEmiStacks().get(0), 1, 0);
					} else if (function.apply(EmiConfig.cheatStackToInventory)) {
						return give(ingredient.getEmiStacks().get(0), ingredient.getEmiStacks().get(0).getItemStack().getMaxCount(), 0);
					} else if (function.apply(EmiConfig.cheatOneToCursor)) {
						return give(ingredient.getEmiStacks().get(0), 1, 1);
					} else if (function.apply(EmiConfig.cheatStackToCursor)) {
						return give(ingredient.getEmiStacks().get(0), ingredient.getEmiStacks().get(0).getItemStack().getMaxCount(), 1);
					}
				}
			}
			if (function.apply(EmiConfig.viewRecipes)) {
				EmiApi.displayRecipes(ingredient);
				if (stack.getRecipeContext() != null) {
					EmiApi.focusRecipe(stack.getRecipeContext());
				}
				return true;
			} else if (function.apply(EmiConfig.viewUses)) {
				EmiApi.displayUses(ingredient);
				return true;
			} else if (function.apply(EmiConfig.favorite)) {
				EmiFavorites.addFavorite(ingredient, stack.getRecipeContext());
				favoriteBatcher.repopulate();
				return true;
			} else if (function.apply(EmiConfig.viewStackTree) && stack.getRecipeContext() != null) {
				BoM.setGoal(stack.getRecipeContext());
				EmiApi.viewRecipeTree();
				return true;
			}
		}
		return false;
	}

	private static boolean give(EmiStack stack, int amount, int mode) {
		if (EmiClient.onServer) {
			PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
			if (stack.getItemStack().isEmpty()) {
				return false;
			}
			buf.writeByte(mode);
			ItemStack is = stack.getItemStack().copy();
			is.setCount(amount);
			buf.writeItemStack(is);
			ClientPlayNetworking.send(EmiMain.CREATE_ITEM, buf);
			return true;
		} else {
			ItemStack is = stack.getItemStack();
			if (!is.isEmpty()) {
				Identifier id = Registry.ITEM.getId(is.getItem());
				String command = "/give @s " + id;
				if (is.hasNbt()) {
					command += is.getNbt().toString();
				}
				command += " " + amount;
				if (command.length() < 256) {
					client.world.sendPacket(new ChatMessageC2SPacket(command));
					return true;
				}
			}
			return false;
		}
	}

	private static class ScreenSpace {
		public final int xMin, xMax, yMin, yMax;
		public final int tx, ty, tw, th;
		public final int pageSize;
		public final boolean rtl;
		public final int[] widths;

		public ScreenSpace(int xMin, int xMax, int yMin, int yMax, int tx, int ty, int tw, int th, boolean rtl, List<Bounds> exclusion) {
			this.xMin = xMin;
			this.xMax = xMax;
			this.yMin = yMin;
			this.yMax = yMax;
			this.tx = tx;
			this.ty = ty;
			this.tw = tw;
			this.th = th;
			this.rtl = rtl;
			int[] widths = new int[th];
			int pageSize = 0;
			for (int y = 0; y < th; y++) {
				int width = 0;
				int cy = ty + y * ENTRY_SIZE;
				outer:
				for (int x = 0; x < tw; x++) {
					int cx = tx + (rtl ? (tw - 1 - x) : x) * ENTRY_SIZE;
					int rx = cx + 18;
					int ry = cy + 18;
					for (Bounds rect : exclusion) {
						if (rect.contains(cx, cy) || rect.contains(rx, cy) || rect.contains(cx, ry) || rect.contains(rx, ry)) {
							break outer;
						}
					}
					width++;
				}
				widths[y] = width;
				pageSize += width;
			}
			this.pageSize = pageSize;
			this.widths = widths;
		}

		public int getWidth(int y) {
			return widths[y];
		}

		public int getX(int x, int y) {
			return tx + (rtl ? x + tw - getWidth(y) : x) * ENTRY_SIZE;
		}

		public int getY(int x, int y) {
			return ty + y * ENTRY_SIZE;
		}

		public int getEdgeX(int off) {
			int t = 0;
			int y = 0;
			while (y < th && t + getWidth(y) < off) {
				t += getWidth(y++);
			}
			return getX(off - t, y);
		}

		public int getEdgeY(int off) {
			int t = 0;
			int y = 0;
			while (y < th && t + getWidth(y) < off) {
				t += getWidth(y++);
			}
			return ty + y * ENTRY_SIZE;
		}

		public int getClosestEdge(int x, int y) {
			if (y < ty) {
				return 0;
			} else if (y >= ty + th * ENTRY_SIZE) {
				return pageSize;
			} else {
				x = (x - xMin) / ENTRY_SIZE;
				y = (y - yMin) / ENTRY_SIZE;
				int off = 0;
				for (int i = 0; i < y; i++) {
					off += widths[i];
				}
				if (x < xMin) {
					return y;
				} else if (x >= xMax) {
					return y + widths[y];
				}
				if (rtl) {
					int to = tw - widths[y];
					if (x >= to) {
						off += x - to;
					}
				} else {
					if (x < widths[y]) {
						off += x;
					} else {
						off += widths[y];
					}
				}
				return off;
			}
		}

		public int getRawOffset(int x, int y) {
			if (x >= 0 && y >= 0 && x < tw && y < th) {
				int off = 0;
				for (int i = 0; i < y; i++) {
					off += widths[i];
				}
				if (rtl) {
					int to = tw - widths[y];
					if (x >= to) {
						return off + x - to;
					}
				} else {
					if (x < widths[y]) {
						return off + x;
					}
				}
			}
			return -1;
		}

		public boolean contains(int x, int y) {
			return x >= xMin && x < xMax && y >= yMin && y < yMax;
		}
	}
}
