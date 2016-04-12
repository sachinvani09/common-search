package org.gbif.common.search.builder;

import org.gbif.api.model.common.search.SpellCheckResponse;

import java.util.Iterator;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

/**
 * Translates a Solr spell check response into a GBIF spell check response.
 */
public class SpellCheckResponseBuilder {

  private static final Joiner COLLATIONS_JOINER = Joiner.on(' ').skipNulls();

  /**
   * Utility classes hide constructors.
   */
  private SpellCheckResponseBuilder(){
    //nothing to do
  }

  public static SpellCheckResponse build(org.apache.solr.client.solrj.response.SpellCheckResponse solrSpellCheckResponse) {

    SpellCheckResponse spellCheckResponse = new SpellCheckResponse();
    spellCheckResponse.setCorrectlySpelled(solrSpellCheckResponse.isCorrectlySpelled());
    ImmutableMap.Builder<String,SpellCheckResponse.Suggestion> mapBuilder = new ImmutableMap.Builder<String,SpellCheckResponse.Suggestion>();
    //If the response contains collations, are use to build the response since those are guarantee to get results
    if (solrSpellCheckResponse.getCollatedResults() != null && !solrSpellCheckResponse.getCollatedResults().isEmpty()) {
      for (org.apache.solr.client.solrj.response.SpellCheckResponse.Collation collation : solrSpellCheckResponse.getCollatedResults()) {
        SpellCheckResponse.Suggestion suggestion = new SpellCheckResponse.Suggestion();
        suggestion.setNumFound(new Long(collation.getNumberOfHits()).intValue());
        StringBuilder token = new StringBuilder();
        StringBuilder spellCorrection = new StringBuilder();
        for (Iterator<org.apache.solr.client.solrj.response.SpellCheckResponse.Correction>  itCorrections = collation.getMisspellingsAndCorrections().iterator();
            itCorrections.hasNext();) {
          org.apache.solr.client.solrj.response.SpellCheckResponse.Correction correction = itCorrections.next();
          token.append(correction.getOriginal());
          spellCorrection.append(correction.getCorrection());
          if (itCorrections.hasNext()) {
            token.append(' ');
            spellCorrection.append(' ');
          }
        }
        suggestion.setAlternatives(Lists.newArrayList(spellCorrection.toString()));
        mapBuilder.put(token.toString(), suggestion);
      }
    } else if (solrSpellCheckResponse.getSuggestionMap() != null && !solrSpellCheckResponse.getSuggestionMap().isEmpty()) {
      for (org.apache.solr.client.solrj.response.SpellCheckResponse.Suggestion solrSuggestion : solrSpellCheckResponse.getSuggestionMap()
        .values()) {
        SpellCheckResponse.Suggestion suggestion = new SpellCheckResponse.Suggestion();
        suggestion.setNumFound(solrSuggestion.getNumFound());
        suggestion.setAlternatives(solrSuggestion.getAlternatives());
        mapBuilder.put(solrSuggestion.getToken(), suggestion);
      }
    }
    spellCheckResponse.setSuggestions(mapBuilder.build());
    return spellCheckResponse;
  }
}
