package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.orbit.tv.core.ReceivedItem

@Composable
fun ReceivedScreen(items: List<ReceivedItem>, onOpen: (ReceivedItem) -> Unit, onDelete: (ReceivedItem) -> Unit, onBack: () -> Unit) {
    var toDelete by remember { mutableStateOf<ReceivedItem?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Back", onBack, icon = Icons.Filled.ArrowBack, modifier = if (items.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier)
            Spacer(Modifier.width(20.dp))
            Column {
                Text("Received", style = MaterialTheme.typography.headlineSmall, color = Orbit.Text)
                Text("${items.size} items · press and hold to delete", style = MaterialTheme.typography.bodyMedium, color = Orbit.Text3)
            }
        }
        Spacer(Modifier.height(24.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No files yet", style = MaterialTheme.typography.titleLarge, color = Orbit.Text)
                    Text("Scan the QR code on the home screen with your iPhone to send something.", color = Orbit.Text3)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    val m = if (item == items.first()) Modifier.focusRequester(firstFocus) else Modifier
                    MediaCard(item, { onOpen(item) }, m, onLongClick = { toDelete = item })
                }
            }
        }
    }

    toDelete?.let { item ->
        Dialog(onDismissRequest = { toDelete = null }) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Column(Modifier.background(Orbit.Surface, Orbit.RadiusLg).padding(32.dp).width(460.dp)) {
                Text("Delete “${item.name}”?", style = MaterialTheme.typography.titleLarge, color = Orbit.Text)
                Text("The file is removed from this TV's storage. This can't be undone.", color = Orbit.Text2, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Keep", { toDelete = null }, Modifier.focusRequester(focus))
                    PrimaryButton("Delete", { onDelete(item); toDelete = null }, icon = Icons.Filled.Delete)
                }
            }
        }
    }
}
