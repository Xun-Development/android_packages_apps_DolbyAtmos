/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.tile

import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.lunaris.dolby.R
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.data.DolbyEffectUnavailableException

class DolbyTileService : TileService() {

    private val repositoryDelegate = lazy {
        try {
            DolbyRepository(applicationContext)
        } catch (e: DolbyEffectUnavailableException) {
            Log.w("DolbyTileService", "Dolby effect is unavailable", e)
            null
        }
    }
    private val repository by repositoryDelegate

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val repository = repository ?: run {
            updateTile()
            return
        }
        val enabled = repository.getDolbyEnabled()
        repository.setDolbyEnabled(!enabled)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val repository = repository
        tile.apply {
            if (repository == null) {
                state = Tile.STATE_UNAVAILABLE
                subtitle = getString(R.string.dolby_unknown)
            } else {
                val enabled = repository.getDolbyEnabled()
                state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                subtitle = getProfileName(repository)
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        if (repositoryDelegate.isInitialized()) repository?.close()
        super.onDestroy()
    }

    private fun getProfileName(repository: DolbyRepository): String {
        val profile = repository.getCurrentProfile()
        val profiles = resources.getStringArray(R.array.dolby_profile_entries)
        val profileValues = resources.getStringArray(R.array.dolby_profile_values)
        
        return try {
            val index = profileValues.indexOf(profile.toString())
            if (index != -1) profiles[index] else getString(R.string.dolby_unknown)
        } catch (e: Exception) {
            getString(R.string.dolby_unknown)
        }
    }
}
