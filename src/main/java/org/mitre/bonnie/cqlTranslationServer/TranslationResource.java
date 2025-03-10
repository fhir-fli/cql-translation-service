package org.mitre.bonnie.cqlTranslationServer;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.File;
import java.io.IOException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.cqframework.cql.cql2elm.CqlCompilerOptions;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.LibraryBuilder;
import org.cqframework.cql.cql2elm.CqlCompilerOptions.Options;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.CqlCompilerException.ErrorSeverity;
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;

/**
 * Root resource (exposed at "translator" path). Uses default per-request
 * life cycle so a new CQL LibraryManager is instantiated and used for each
 * request that can include a batch of related CQL files.
 */
@Path("translator")
public class TranslationResource {

  public static final String CQL_TEXT_TYPE = "application/cql";
  public static final String ELM_XML_TYPE = "application/elm+xml";
  public static final String ELM_JSON_TYPE = "application/elm+json";
  public static final String TARGET_FORMAT = "X-TargetFormat";
  public static final MultivaluedMap<String, Options> PARAMS_TO_OPTIONS_MAP = new MultivaluedHashMap<String, Options>() {{
    putSingle("date-range-optimization", Options.EnableDateRangeOptimization);
    putSingle("annotations", Options.EnableAnnotations);
    putSingle("locators", Options.EnableLocators);
    putSingle("result-types", Options.EnableResultTypes);
    putSingle("detailed-errors", Options.EnableDetailedErrors);
    putSingle("disable-list-traversal", Options.DisableListTraversal);
    putSingle("disable-list-demotion", Options.DisableListDemotion);
    putSingle("disable-list-promotion", Options.DisableListPromotion);
    putSingle("enable-interval-demotion", Options.EnableIntervalDemotion);
    putSingle("enable-interval-promotion", Options.EnableIntervalPromotion);
    putSingle("disable-method-invocation", Options.DisableMethodInvocation);
    putSingle("require-from-keyword", Options.RequireFromKeyword);
    put("strict", Arrays.asList(
            Options.DisableListTraversal,
            Options.DisableListDemotion,
            Options.DisableListPromotion,
            Options.DisableMethodInvocation)
    );
    put("debug", Arrays.asList(
            Options.EnableAnnotations,
            Options.EnableLocators,
            Options.EnableResultTypes)
    );
  }};

  @POST
  @Consumes(CQL_TEXT_TYPE)
  @Produces(ELM_XML_TYPE)
  public Response cqlToElmXml(File cql, @Context UriInfo info) {
    try {
      LibraryManager libraryManager = this.getLibraryManager(info.getQueryParameters());
      CqlTranslator translator = CqlTranslator.fromFile(cql, libraryManager);
      ResponseBuilder resp = getResponse(translator);
      resp = resp.entity(translator.toXml()).type(ELM_XML_TYPE);
      return resp.build();
    } catch (IOException e) {
      throw new TranslationFailureException("Unable to read request");
    }

  }

  @POST
  @Consumes(CQL_TEXT_TYPE)
  @Produces(ELM_JSON_TYPE)
  public Response cqlToElmJson(File cql, @Context UriInfo info) {
    try {
      LibraryManager libraryManager = this.getLibraryManager(info.getQueryParameters());
      CqlTranslator translator = CqlTranslator.fromFile(cql, libraryManager);
      ResponseBuilder resp = getResponse(translator);
      resp = resp.entity(translator.toJson()).type(ELM_JSON_TYPE);
      return resp.build();
    } catch (IOException e) {
      throw new TranslationFailureException("Unable to read request");
    }
  }

  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.MULTIPART_FORM_DATA)
  public Response cqlPackageToElmPackage(
          FormDataMultiPart pkg,
          @HeaderParam(TARGET_FORMAT) @DefaultValue(ELM_JSON_TYPE) List<String> targetFormats,
          @Context UriInfo info
  ) {
    // Jersey doesn't support parsing multiple value headers by comma, so we need to do it
	  // for ourselves. See https://github.com/jersey/jersey/issues/2436
    try {
      LibraryManager libraryManager = this.getLibraryManager(info.getQueryParameters());
      libraryManager.getLibrarySourceLoader().registerProvider(new MultipartLibrarySourceProvider(pkg));
      FormDataMultiPart translatedPkg = new FormDataMultiPart();
      for (String fieldId: pkg.getFields().keySet()) {
        for (FormDataBodyPart part: pkg.getFields(fieldId)) {
          CqlTranslator translator = CqlTranslator.fromFile(part.getEntityAs(File.class), libraryManager);
          for( String format : targetFormats ) {
            for( String subformat : format.split(",") ) {
              MediaType targetFormat = MediaType.valueOf( subformat );
              if (targetFormat.equals(MediaType.valueOf(ELM_XML_TYPE))) {
                translatedPkg.field(fieldId, translator.toXml(), targetFormat);
              } else if (targetFormat.equals(MediaType.valueOf(ELM_JSON_TYPE))) {
                translatedPkg.field(fieldId, translator.toJson(), targetFormat);
              } else {
                throw new TranslationFailureException("Unsupported media type");
              }
            }
          }
        }
      }
      ResponseBuilder resp = Response.ok().type(MediaType.MULTIPART_FORM_DATA).entity(translatedPkg);
      return resp.build();
    } catch (IOException ex) {
      throw new TranslationFailureException("Unable to read request");
    }
  }

  private LibraryManager getLibraryManager(MultivaluedMap<String, String> params) {
    LibraryBuilder.SignatureLevel signatureLevel = LibraryBuilder.SignatureLevel.None;
    List<Options> optionsList = new ArrayList<>();
    for (String key: params.keySet()) {
      if (PARAMS_TO_OPTIONS_MAP.containsKey(key) && Boolean.parseBoolean(params.getFirst(key))) {
        optionsList.addAll(PARAMS_TO_OPTIONS_MAP.get(key));
      } else if (key.equals("signatures")) {
        signatureLevel = LibraryBuilder.SignatureLevel.valueOf(params.getFirst("signatures"));
      }
    }
    Options[] options = optionsList.toArray(new Options[optionsList.size()]);
    LibraryManager libraryManager = new LibraryManager(new ModelManager(), new CqlCompilerOptions(ErrorSeverity.Info, signatureLevel, options));
    // FHIR library source provider is always needed for FHIR and harmless for other models
    libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());
    return libraryManager;
  }

  private ResponseBuilder getResponse(CqlTranslator translator) {
    return translator.getErrors().size() > 0
            ? Response.status(Status.BAD_REQUEST) : Response.ok();
  }

}
