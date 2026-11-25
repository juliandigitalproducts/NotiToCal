package de.juliandigitalproducts.notitocal.data

import kotlinx.serialization.Serializable

/** A term replaced before a language-model request, and the alias written in its place. */
@Serializable
data class AliasEntry(
  val term: String = "",
  val alias: String = "",
)
