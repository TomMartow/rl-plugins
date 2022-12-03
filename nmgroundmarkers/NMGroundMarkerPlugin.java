/*
 * Copyright (c) 2018, TheLonelyDev <https://github.com/TheLonelyDev>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.nmgroundmarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
	name = "NM Ground Markers",
	description = "Enable marking of tiles using the Shift key",
	tags = {"overlay", "tiles"}
)
public class NMGroundMarkerPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "nmgroundMarker";
	private static final String MARK = "Mark tile";
	private static final String UNMARK = "Unmark tile";
	private static final String LABEL = "Label tile";
	private static final String WALK_HERE = "Walk here";
	private static final String REGION_PREFIX = "region_";

	@Getter(AccessLevel.PACKAGE)
	private final List<NMColorTileMarker> points = new ArrayList<>();

	@Inject
	private Client client;

	@Inject
	private NMGroundMarkerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private NMGroundMarkerOverlay overlay;

	@Inject
	private NMGroundMarkerMinimapOverlay minimapOverlay;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private NMGroundMarkerSharingManager sharingManager;

	@Inject
	private Gson gson;

	void savePoints(int regionId, Collection<NMGroundMarkerPoint> points)
	{
		if (points == null || points.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
			return;
		}

		String json = gson.toJson(points);
		configManager.setConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId, json);
	}

	Collection<NMGroundMarkerPoint> getPoints(int regionId)
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
		if (Strings.isNullOrEmpty(json))
		{
			return Collections.emptyList();
		}

		// CHECKSTYLE:OFF
		return gson.fromJson(json, new TypeToken<List<NMGroundMarkerPoint>>(){}.getType());
		// CHECKSTYLE:ON
	}

	@Provides
	NMGroundMarkerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NMGroundMarkerConfig.class);
	}

	void loadPoints()
	{
		points.clear();

		int[] regions = client.getMapRegions();

		if (regions == null)
		{
			return;
		}

		for (int regionId : regions)
		{
			// load points for region
			log.debug("Loading points for region {}", regionId);
			Collection<NMGroundMarkerPoint> regionPoints = getPoints(regionId);
			Collection<NMColorTileMarker> NMColorTileMarkers = translateToColorTileMarker(regionPoints);
			points.addAll(NMColorTileMarkers);
		}
	}

	/**
	 * Translate a collection of ground marker points to color tile markers, accounting for instances
	 *
	 * @param points {@link NMGroundMarkerPoint}s to be converted to {@link NMColorTileMarker}s
	 * @return A collection of color tile markers, converted from the passed ground marker points, accounting for local
	 *         instance points. See {@link WorldPoint#toLocalInstance(Client, WorldPoint)}
	 */
	private Collection<NMColorTileMarker> translateToColorTileMarker(Collection<NMGroundMarkerPoint> points)
	{
		if (points.isEmpty())
		{
			return Collections.emptyList();
		}

		return points.stream()
			.map(point -> new NMColorTileMarker(
				WorldPoint.fromRegion(point.getRegionId(), point.getRegionX(), point.getRegionY(), point.getZ()),
				point.getColor(), point.getLabel()))
			.flatMap(colorTile ->
			{
				final Collection<WorldPoint> localWorldPoints = WorldPoint.toLocalInstance(client, colorTile.getWorldPoint());
				return localWorldPoints.stream().map(wp -> new NMColorTileMarker(wp, colorTile.getColor(), colorTile.getLabel()));
			})
			.collect(Collectors.toList());
	}

	@Override
	public void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(minimapOverlay);
		if (config.showImportExport())
		{
			sharingManager.addImportExportMenuOptions();
			sharingManager.addClearMenuOption();
		}
		loadPoints();
		eventBus.register(sharingManager);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(sharingManager);
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		sharingManager.removeMenuOptions();
		points.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// map region has just been updated
		loadPoints();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		final boolean hotKeyPressed = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (hotKeyPressed && event.getOption().equals(WALK_HERE))
		{
			final Tile selectedSceneTile = client.getSelectedSceneTile();

			if (selectedSceneTile == null)
			{
				return;
			}

			final WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, selectedSceneTile.getLocalLocation());
			final int regionId = worldPoint.getRegionID();
			final NMGroundMarkerPoint point = new NMGroundMarkerPoint(regionId, worldPoint.getRegionX(), worldPoint.getRegionY(), worldPoint.getPlane(), null, null);
			final boolean exists = getPoints(regionId).contains(point);

			client.createMenuEntry(-1)
				.setOption(exists ? UNMARK : MARK)
				.setTarget(event.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e ->
				{
					Tile target = client.getSelectedSceneTile();
					if (target != null)
					{
						markTile(target.getLocalLocation());
					}
				});

			if (exists)
			{
				client.createMenuEntry(-2)
					.setOption(LABEL)
					.setTarget(event.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e ->
					{
						Tile target = client.getSelectedSceneTile();
						if (target != null)
						{
							labelTile(target);
						}
					});
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(NMGroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP)
			&& event.getKey().equals(NMGroundMarkerConfig.SHOW_IMPORT_EXPORT_KEY_NAME))
		{
			sharingManager.removeMenuOptions();

			if (config.showImportExport())
			{
				sharingManager.addImportExportMenuOptions();
				sharingManager.addClearMenuOption();
			}
		}
	}

	private void markTile(LocalPoint localPoint)
	{
		if (localPoint == null)
		{
			return;
		}

		WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, localPoint);

		int regionId = worldPoint.getRegionID();
		NMGroundMarkerPoint point = new NMGroundMarkerPoint(regionId, worldPoint.getRegionX(), worldPoint.getRegionY(), worldPoint.getPlane(), config.markerColor(), null);
		log.debug("Updating point: {} - {}", point, worldPoint);

		List<NMGroundMarkerPoint> NMGroundMarkerPoints = new ArrayList<>(getPoints(regionId));
		if (NMGroundMarkerPoints.contains(point))
		{
			NMGroundMarkerPoints.remove(point);
		}
		else
		{
			NMGroundMarkerPoints.add(point);
		}

		savePoints(regionId, NMGroundMarkerPoints);

		loadPoints();
	}

	private void labelTile(Tile tile)
	{
		LocalPoint localPoint = tile.getLocalLocation();
		WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, localPoint);
		final int regionId = worldPoint.getRegionID();

		NMGroundMarkerPoint searchPoint = new NMGroundMarkerPoint(regionId, worldPoint.getRegionX(), worldPoint.getRegionY(), worldPoint.getPlane(), null, null);
		Collection<NMGroundMarkerPoint> points = getPoints(regionId);
		NMGroundMarkerPoint existing = points.stream()
			.filter(p -> p.equals(searchPoint))
			.findFirst().orElse(null);
		if (existing == null)
		{
			return;
		}

		chatboxPanelManager.openTextInput("Tile label")
			.value(Optional.ofNullable(existing.getLabel()).orElse(""))
			.onDone((input) ->
			{
				input = Strings.emptyToNull(input);

				NMGroundMarkerPoint newPoint = new NMGroundMarkerPoint(regionId, worldPoint.getRegionX(), worldPoint.getRegionY(), worldPoint.getPlane(), existing.getColor(), input);
				points.remove(searchPoint);
				points.add(newPoint);
				savePoints(regionId, points);

				loadPoints();
			})
			.build();
	}
}
