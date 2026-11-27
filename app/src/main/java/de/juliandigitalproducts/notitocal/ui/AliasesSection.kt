// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AliasEntry

/** Editable term → alias list used before language-model requests. */
@Composable
fun AliasesSection() {
  val s by Ui.app.settings.state.collectAsState()
  var editing by remember { mutableStateOf<Int?>(null) }
  var term by remember { mutableStateOf("") }
  var alias by remember { mutableStateOf("") }

  fun open(index: Int?) {
    editing = index ?: -1
    if (index != null && index in s.aliases.indices) {
      term = s.aliases[index].term
      alias = s.aliases[index].alias
    } else {
      term = ""
      alias = ""
    }
  }

  SettingGroupCard(title = stringResource(R.string.aliases_title)) {
    Hint(stringResource(R.string.aliases_body))
    SettingSwitch(stringResource(R.string.aliases_restore), s.restoreAliases, stringResource(R.string.aliases_restore_body)) { v ->
      Ui.update { it.copy(restoreAliases = v) }
    }
    if (s.aliases.isEmpty()) Hint(stringResource(R.string.aliases_empty))
    s.aliases.forEachIndexed { index, entry ->
      ListItem(
        headlineContent = { Text(entry.term) },
        supportingContent = { Text(entry.alias) },
        colors = cardListItemColors(),
        modifier = Modifier.fillMaxWidth().clickable { open(index) },
        trailingContent = {
          Row {
            IconButton(onClick = { open(index) }) { Icon(Icons.Filled.Edit, stringResource(R.string.alias_edit)) }
            IconButton(onClick = {
              Ui.update { current -> current.copy(aliases = current.aliases.filterIndexed { i, _ -> i != index }) }
            }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
          }
        },
      )
    }
    OutlinedButton(onClick = { open(null) }, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
      Text(stringResource(R.string.aliases_add))
    }
  }

  if (editing != null) {
    AlertDialog(
      onDismissRequest = { editing = null },
      title = { Text(stringResource(if (editing!! >= 0) R.string.alias_edit else R.string.aliases_add)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(term, { term = it }, label = { Text(stringResource(R.string.aliases_term)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.aliases_alias)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        TextButton(onClick = {
          val cleanTerm = term.trim()
          val cleanAlias = alias.trim()
          if (cleanTerm.isNotEmpty() && cleanAlias.isNotEmpty()) {
            val index = editing
            Ui.update { current ->
              val next = current.aliases.toMutableList()
              val entry = AliasEntry(cleanTerm, cleanAlias)
              if (index != null && index >= 0 && index < next.size) next[index] = entry else next += entry
              current.copy(aliases = next)
            }
          }
          editing = null
        }) { Text(stringResource(R.string.alias_save)) }
      },
      dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}
