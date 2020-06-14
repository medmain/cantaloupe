package edu.illinois.library.cantaloupe.resource.iiif.v2;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import edu.illinois.library.cantaloupe.cache.CacheFacade;
import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.http.Method;
import edu.illinois.library.cantaloupe.http.Status;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.image.Info;
import edu.illinois.library.cantaloupe.image.MediaType;
import edu.illinois.library.cantaloupe.processor.Processor;
import edu.illinois.library.cantaloupe.processor.ProcessorConnector;
import edu.illinois.library.cantaloupe.processor.ProcessorFactory;
import edu.illinois.library.cantaloupe.resource.JacksonRepresentation;
import edu.illinois.library.cantaloupe.resource.ResourceException;
import edu.illinois.library.cantaloupe.resource.Route;
import edu.illinois.library.cantaloupe.source.Source;
import edu.illinois.library.cantaloupe.source.SourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles IIIF Image API 2.x information requests.
 *
 * @see <a href="http://iiif.io/api/image/2.1/#information-request">Information
 *      Requests</a>
 */
public class InformationResource extends IIIF2Resource {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InformationResource.class);

    private static final Method[] SUPPORTED_METHODS =
            new Method[] { Method.GET, Method.OPTIONS };

    @Override
    protected Logger getLogger() {
        return LOGGER;
    }

    @Override
    public Method[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    /**
     * Writes a JSON-serialized {@link ImageInfo} instance to the response.
     */
    @Override
    public void doGET() throws Exception {
        if (redirectToNormalizedScaleConstraint()) {
            return;
        }
        try {
            // The logic here is somewhat convoluted. See the method
            // documentation for more information.
            if (!authorize()) {
                return;
            }
        } catch (ResourceException e) {
            if (Status.FORBIDDEN.equals(e.getStatus())) {
                throw e;
            }
            // Continue anyway. All we needed was to set the response status:
            // https://iiif.io/api/auth/1.0/#interaction-with-access-controlled-resources
        }

        final Configuration config = Configuration.getInstance();
        final Identifier identifier = getIdentifier();
        final CacheFacade cacheFacade = new CacheFacade();

        // If we are using a cache, and don't need to resolve first, and the
        // cache contains an info matching the request, skip all the setup and
        // just return the cached info.
        if (!isBypassingCache() && !isResolvingFirst()) {
            try {
                Info info = cacheFacade.getInfo(identifier);
                if (info != null) {
                    // The source format will be null or UNKNOWN if the info was
                    // serialized in version < 3.4.
                    final Format format = info.getSourceFormat();
                    if (format != null && !Format.UNKNOWN.equals(format)) {
                        final Processor processor = new ProcessorFactory().
                                newProcessor(format);
                        addHeaders();
                        newRepresentation(info, processor)
                                .write(getResponse().getOutputStream());
                        return;
                    }
                }
            } catch (IOException e) {
                // Don't rethrow -- it's still possible to service the request.
                LOGGER.error(e.getMessage());
            }
        }

        final Source source = new SourceFactory().newSource(
                identifier, getDelegateProxy());

        // If we are resolving first, or if the source image is not present in
        // the source cache (if enabled), check access to it in preparation for
        // retrieval.
        final Path sourceImage = cacheFacade.getSourceCacheFile(identifier);
        if (sourceImage == null || isResolvingFirst()) {
            try {
                source.checkAccess();
            } catch (NoSuchFileException e) { // this needs to be rethrown!
                if (config.getBoolean(Key.CACHE_SERVER_PURGE_MISSING, false)) {
                    // If the image was not found, purge it from the cache.
                    cacheFacade.purgeAsync(identifier);
                }
                throw e;
            }
        }

        // Get the format of the source image.
        // If we are not resolving first, and there is a hit in the source
        // cache, read the format from the source-cached-file, as we will
        // expect source cache access to be more efficient.
        // Otherwise, read it from the source.
        Format format = Format.UNKNOWN;
        if (!isResolvingFirst() && sourceImage != null) {
            List<MediaType> mediaTypes = MediaType.detectMediaTypes(sourceImage);
            if (!mediaTypes.isEmpty()) {
                format = mediaTypes.get(0).toFormat();
            }
        } else {
            format = source.getFormat();
        }

        // Obtain an instance of the processor assigned to that format.
        try (Processor processor = new ProcessorFactory().newProcessor(format)) {
            // Connect it to the source.
            tempFileFuture = new ProcessorConnector().connect(
                    source, processor, identifier, format);

            final Info info = getOrReadInfo(identifier, processor);

            addHeaders();
            newRepresentation(info, processor)
                    .write(getResponse().getOutputStream());
        }
    }

    private void addHeaders() {
        getResponse().setHeader("Content-Type", getNegotiatedMediaType());
    }

    /**
     * @return Full image URI corresponding to the given identifier, respecting
     *         the {@literal X-Forwarded-*} and
     *         {@link #PUBLIC_IDENTIFIER_HEADER} reverse proxy headers.
     */
    private String getImageURI() {
        return getPublicRootReference() + Route.IIIF_2_PATH + "/" +
                getPublicIdentifier();
    }

    private String getNegotiatedMediaType() {
        String mediaType;
        // If the client has requested JSON-LD, set the content type to
        // that; otherwise set it to JSON.
        final List<String> preferences = getPreferredMediaTypes();
        if (!preferences.isEmpty() && preferences.get(0)
                .startsWith("application/ld+json")) {
            mediaType = "application/ld+json";
        } else {
            mediaType = "application/json";
        }
        return mediaType + ";charset=UTF-8";
    }

    private JacksonRepresentation newRepresentation(Info info,
                                                    Processor processor) {
        final ImageInfoFactory factory = new ImageInfoFactory(
                processor.getSupportedFeatures(),
                processor.getSupportedIIIF2Qualities(),
                processor.getAvailableOutputFormats());
        factory.setDelegateProxy(getDelegateProxy());

        final ImageInfo<String, Object> imageInfo = factory.newImageInfo(
                getImageURI(), info, getPageIndex(), getScaleConstraint());
        return new JacksonRepresentation(imageInfo);
    }

    private boolean isResolvingFirst() {
        return Configuration.getInstance().
                getBoolean(Key.CACHE_SERVER_RESOLVE_FIRST, true);
    }

}
