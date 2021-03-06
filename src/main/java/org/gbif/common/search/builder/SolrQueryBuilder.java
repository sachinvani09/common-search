/*
 * Copyright 2011 Global Biodiversity Information Facility (GBIF)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gbif.common.search.builder;

import org.gbif.api.model.common.paging.Pageable;
import org.gbif.api.model.common.search.FacetedSearchRequest;
import org.gbif.api.model.common.search.SearchParameter;
import org.gbif.api.model.common.search.SearchRequest;
import org.gbif.common.search.model.FacetField;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.BiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.params.FacetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.common.search.builder.SolrQueryUtils.taggedField;
import static org.gbif.common.search.util.AnnotationUtils.initFacetFieldDefs;
import static org.gbif.common.search.util.AnnotationUtils.initFacetFieldsPropertiesMap;
import static org.gbif.common.search.util.AnnotationUtils.initFieldsPropertiesMap;
import static org.gbif.common.search.util.QueryUtils.PARAMS_JOINER;
import static org.gbif.common.search.util.QueryUtils.isNegated;
import static org.gbif.common.search.util.QueryUtils.removeNegation;
import static org.gbif.common.search.util.QueryUtils.setFacetMethod;
import static org.gbif.common.search.util.QueryUtils.setQueryPaging;
import static org.gbif.common.search.util.QueryUtils.setRequestHandler;
import static org.gbif.common.search.util.SolrConstants.ALT_QUERY_PARAM;
import static org.gbif.common.search.util.SolrConstants.DEFAULT_QUERY;
import static org.gbif.common.search.util.SolrConstants.FACET_FILTER_EX;
import static org.gbif.common.search.util.SolrConstants.HL_FRAGMENT_SIZE;
import static org.gbif.common.search.util.SolrConstants.NOT_OP;
import static org.gbif.common.search.util.SolrConstants.NUM_HL_SNIPPETS;
import static org.gbif.common.search.util.SolrConstants.PARAM_FACET_MISSING;
import static org.gbif.common.search.util.SolrConstants.PARAM_FACET_SORT;

import static org.gbif.common.search.builder.SolrQueryUtils.DEFAULT_FACET_COUNT;
import static org.gbif.common.search.builder.SolrQueryUtils.DEFAULT_FACET_MISSING;
import static org.gbif.common.search.builder.SolrQueryUtils.DEFAULT_FACET_SORT;
import static org.gbif.common.search.builder.SolrQueryUtils.perFieldParamName;
import static org.gbif.common.search.builder.SolrQueryUtils.getInterpretedValue;
import static org.gbif.common.search.builder.SolrQueryUtils.buildFilterQuery;


/**
 * Builder class that helps in the creation process of {@link SolrQuery} objects from {@link SearchRequest} objects.
 * The build method is not thread safe, supports the cloneable interface for allowing holds a partial state,
 * that can be cloned and repeated in other instances.
 *
 * @param <T> is the type of the annotated class that holds the mapping information.
 * @param <P> is the SearchParameter Enum type used for enumerate the supported facets / search parameter.
 */
public class SolrQueryBuilder<T, P extends Enum<?> & SearchParameter> {


  private static final Logger LOG = LoggerFactory.getLogger(SolrQueryBuilder.class);

  private QueryStringBuilderBase queryBuilder;

  // Request request handler
  private String requestHandler;
  // Search parameter/facet enumeration
  private final Class<P> searchParameterEnum;
  // a map containing the solr fields vs. facet enum members
  private final BiMap<String, P> facetFieldsPropertiesMap;
  // keyed on solr field names
  private final Map<String, FacetField> facetFieldDefs;
  // a map containing the solr fields vs. property name
  private final BiMap<String, String> fieldsPropertiesMap;
  // the value used for an optional sort order applied to a search via param "sort"
  private Map<String, SolrQuery.ORDER> primarySortOrder;

  /**
   * Full private constructor.
   */
  private SolrQueryBuilder(Class<P> searchParameterEnum, BiMap<String, String> fieldsPropertiesMap,
      BiMap<String, P> facetFieldsPropertiesMap, Map<String, FacetField> facetFieldDefs) {
    this.searchParameterEnum = searchParameterEnum;
    this.facetFieldsPropertiesMap = facetFieldsPropertiesMap;
    this.fieldsPropertiesMap = fieldsPropertiesMap;
    this.facetFieldDefs = facetFieldDefs;
  }

  /**
   * Default private constructor.
   */
  private SolrQueryBuilder(Class<P> searchParameterEnum, Class<T> annotatedClass) {
    this.searchParameterEnum = searchParameterEnum;
    facetFieldsPropertiesMap = initFacetFieldsPropertiesMap(annotatedClass, searchParameterEnum);
    fieldsPropertiesMap = initFieldsPropertiesMap(annotatedClass);
    facetFieldDefs = initFacetFieldDefs(annotatedClass);
  }

  /**
   * Default factory method.
   */
  public static <T, P extends Enum<?> & SearchParameter> SolrQueryBuilder<T, P> create(Class<P> searchParameterEnum,
      Class<T> annotatedClass) {
    return new SolrQueryBuilder<T, P>(searchParameterEnum, annotatedClass);
  }

  /**
   * Builds a SolrQuery instance using the searchRequest.
   */
  public SolrQuery build(final FacetedSearchRequest<P> searchRequest) {
    // get regular search without facets
    SolrQuery solrQuery = build((SearchRequest<P>) searchRequest);
    // add facets
    applyFacetSettings(searchRequest, solrQuery);

    LOG.debug("Solr faceted query build: {}", solrQuery);
    return solrQuery;
  }

  public SolrQuery build(final SearchRequest<P> searchRequest) {
    SolrQuery solrQuery = new SolrQuery();
    // q param
    solrQuery.setQuery(queryBuilder.build(searchRequest));
    // paging
    setQueryPaging(searchRequest, solrQuery);
    // sets the default, alternative query
    solrQuery.set(ALT_QUERY_PARAM, DEFAULT_QUERY);
    // filters
    setQueryFilter(searchRequest, solrQuery);
    // highlight
    setHighLightParams(searchRequest, solrQuery);
    // sorting
    setPrimarySortOrder(solrQuery);
    // set shards/for distributed search
    setRequestHandler(solrQuery, requestHandler);

    LOG.debug("Solr query build: {}", solrQuery);
    return solrQuery;
  }

  /**
   * Copies the object's content into a new object.
   */
  public SolrQueryBuilder<T, P> getCopy() {
    SolrQueryBuilder<T, P> searchRequestBuilder =
        new SolrQueryBuilder<T, P>(searchParameterEnum, fieldsPropertiesMap, facetFieldsPropertiesMap, facetFieldDefs);
    searchRequestBuilder.queryBuilder = queryBuilder;
    searchRequestBuilder.primarySortOrder = primarySortOrder;
    searchRequestBuilder.requestHandler = requestHandler;
    return searchRequestBuilder;
  }

  /**
   * Sets the primarySortOrder names for the distributed query.
   *
   * @param primarySortOrder list of sort orders for mapped fields.
   * @return the current builder instance.
   */
  public SolrQueryBuilder<T, P> withPrimarySortOrder(final Map<String, SolrQuery.ORDER> primarySortOrder) {
    this.primarySortOrder = primarySortOrder;
    return this;
  }

  /**
   * Adds a {@link QueryStringBuilderBase} to the builder instance.
   *
   * @param queryBuilder to set
   * @return the current builder instance
   */
  public SolrQueryBuilder<T, P> withQueryBuilder(final QueryStringBuilderBase queryBuilder) {
    this.queryBuilder = queryBuilder;
    return this;
  }

  /**
   * Adds a Request Handler to the builder instance.
   * This is particularly useful when an handler contains default settings hidden in it; e.g: distributed search.
   *
   * @param requestHandler to set
   * @return the current builder instance
   */
  public SolrQueryBuilder<T, P> withRequestHandler(final String requestHandler) {
    this.requestHandler = requestHandler;
    return this;
  }

  /**
   * Helper method that sets the parameter for a faceted query.
   *
   * @param searchRequest the searchRequest used to extract the parameters
   * @param solrQuery this object is modified by adding the facets parameters
   */
  private void applyFacetSettings(FacetedSearchRequest<P> searchRequest, SolrQuery solrQuery) {

    BiMap<P, String> facetFieldsMapInv = facetFieldsPropertiesMap.inverse();

    if (!searchRequest.getFacets().isEmpty()) {
      // Only show facets that contains at least 1 record
      solrQuery.setFacet(true);
      // defaults if not overridden on per field basis
      solrQuery.setFacetMinCount(MoreObjects.firstNonNull(searchRequest.getFacetMinCount(), DEFAULT_FACET_COUNT));
      solrQuery.setFacetMissing(DEFAULT_FACET_MISSING);
      solrQuery.setFacetSort(DEFAULT_FACET_SORT.toString().toLowerCase());

      if(searchRequest.getFacetLimit() != null) {
        solrQuery.setFacetLimit(searchRequest.getFacetLimit());
      }

      if(searchRequest.getFacetOffset() != null) {
        solrQuery.setParam(FacetParams.FACET_OFFSET,searchRequest.getFacetOffset().toString());
      }

      for (final P facet : searchRequest.getFacets()) {
        if (!facetFieldsMapInv.containsKey(facet)) {
          LOG.warn("{} is no valid facet. Ignore", facet);
          continue;
        }
        final String field = facetFieldsMapInv.get(facet);
        if (searchRequest.isMultiSelectFacets()) {
          // use exclusion filter with same name as used in filter query
          // http://wiki.apache.org/solr/SimpleFacetParameters#Tagging_and_excluding_Filters
          solrQuery.addFacetField(taggedField(field,FACET_FILTER_EX));
        } else {
          solrQuery.addFacetField(field);
        }
        FacetField fieldDef = facetFieldDefs.get(field);
        if (fieldDef.missing() != DEFAULT_FACET_MISSING) {
          solrQuery.setParam(perFieldParamName(field, PARAM_FACET_MISSING), fieldDef.missing());
        }
        if (fieldDef.sort() != DEFAULT_FACET_SORT) {
          solrQuery.setParam(perFieldParamName(field, PARAM_FACET_SORT), fieldDef.sort().toString().toLowerCase());
        }
        setFacetMethod(solrQuery, field, fieldDef.method());
        Pageable facetPage = searchRequest.getFacetPage(facet);
        if (facetPage != null) {
          solrQuery.setParam(perFieldParamName(field, FacetParams.FACET_OFFSET), Long.toString(facetPage.getOffset()));
          solrQuery.setParam(perFieldParamName(field, FacetParams.FACET_LIMIT), Integer.toString(facetPage.getLimit()));
        }
      }
    }
  }

  /**
   * Helper method that sets the highlighting parameters.
   *
   * @param searchRequest the searchRequest used to extract the parameters
   * @param solrQuery this object is modified by adding the facets parameters
   */
  private void setHighLightParams(SearchRequest<P> searchRequest, SolrQuery solrQuery) {
    solrQuery.setHighlight(searchRequest.isHighlight());
    solrQuery.setHighlightSnippets(NUM_HL_SNIPPETS);
    solrQuery.setHighlightFragsize(HL_FRAGMENT_SIZE);
    if (searchRequest.isHighlight()) {
      for (String hlField : queryBuilder.getHighlightedFields()) {
        solrQuery.addHighlightField(hlField);
      }
    }
  }

  /**
   * Sets the primary sort order query information.
   *
   * @param solrQuery to be modified.
   */
  private void setPrimarySortOrder(SolrQuery solrQuery) {
    if (primarySortOrder != null) {
      for (Map.Entry<String, SolrQuery.ORDER> so : primarySortOrder.entrySet()) {
        solrQuery.addSort(so.getKey(), so.getValue());
      }
    }
  }

  /**
   * Sets the query filters.
   * Takes a {@link Multimap} and produces a Solr filter query containing the filtering criteria.
   * The output string will be in the format: (param1:vp1.1 OR param1:vp1.2) AND (param2:vf2.1 OR param2:vp2.2)...
   * The param-i key are taken from the key sets of the map and the vpi.j (value j of param i) are the entry set of the
   * map.
   *
   * @return the String containing a Solr filter query
   */
  private void setQueryFilter(final SearchRequest<P> searchRequest, SolrQuery solrQuery) {
    Multimap<P, String> params = searchRequest.getParameters();
    boolean isFacetedRequest = FacetedSearchRequest.class.isAssignableFrom(searchRequest.getClass());
    if (params != null) {
      BiMap<P, String> fieldsPropertiesMapInv = facetFieldsPropertiesMap.inverse();
      for (P param : params.keySet()) {
        if (param != null && !fieldsPropertiesMapInv.containsKey(param)) {
          LOG.warn("Unknown search parameter {}", param);
          continue;
        }
        // solr field for this parameter
        String solrFieldName = fieldsPropertiesMapInv.get(param);
        Collection<String> paramValues = params.get(param);
        List<String> filterQueriesComponents = Lists.newArrayListWithCapacity(paramValues.size());
        for (String paramValue : paramValues) {
          // Negate expression is removed if it is present
          String interpretedValue = getInterpretedValue(param.type(), removeNegation(paramValue));
          String queryComponent = PARAMS_JOINER.join(solrFieldName, interpretedValue);

          filterQueriesComponents.add(isNegated(paramValue) ? NOT_OP + queryComponent : queryComponent);
        }
        if (!filterQueriesComponents.isEmpty()) { // there are filter queries for this parameter
          StringBuilder filterQuery = buildFilterQuery(isFacetedRequest, solrFieldName, filterQueriesComponents);
          solrQuery.addFilterQuery(filterQuery.toString());
        }

      }
    }
  }

}
