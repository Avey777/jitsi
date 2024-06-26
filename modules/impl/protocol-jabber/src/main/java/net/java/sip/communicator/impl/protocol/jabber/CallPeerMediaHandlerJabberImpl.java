/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import java.lang.reflect.*;
import java.util.*;
import java.util.List;

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension.SendersEnum;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.JingleUtils;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.media.*;

import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.utils.*;
import org.jivesoftware.smack.SmackException.*;
import org.jivesoftware.smackx.disco.packet.*;

import ch.imvs.sdes4j.srtp.*;

/**
 * An XMPP specific extension of the generic media handler.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 * @author Hristo Terezov
 * @author Boris Grozev
 */
public class CallPeerMediaHandlerJabberImpl
    extends CallPeerMediaHandler<CallPeerJabberImpl>
{
    /**
     * The <tt>Logger</tt> used by the <tt>CallPeerMediaHandlerJabberImpl</tt>
     * class and its instances for logging output.
     */
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CallPeerMediaHandlerJabberImpl.class);

    /**
     * Determines whether a specific XMPP feature is supported by both a
     * specific <tt>ScServiceDiscoveryManager</tt> (may be referred to as the
     * local peer) and a specific <tt>DiscoverInfo</tt> (may be thought of as
     * the remote peer).
     *
     * @param discoveryManager the <tt>ScServiceDiscoveryManager</tt> to be
     * checked whether it includes the specified feature
     * @param discoverInfo the <tt>DiscoveryInfo</tt> which is to be checked
     * whether it contains the specified feature. If <tt>discoverInfo</tt> is
     * <tt>null</tt>, it is considered to contain the specified feature.
     * @param feature the feature to be determined whether it is supported by
     * both the specified <tt>discoveryManager</tt> and the specified
     * <tt>discoverInfo</tt>
     * @return <tt>true</tt> if the specified <tt>feature</tt> is supported by
     * both the specified <tt>discoveryManager</tt> and the specified
     * <tt>discoverInfo</tt>; otherwise, <tt>false</tt>
     */
    private static boolean isFeatureSupported(
            ScServiceDiscoveryManager discoveryManager,
            DiscoverInfo discoverInfo,
            String feature)
    {
        return
            discoveryManager.includesFeature(feature)
                && ((discoverInfo == null)
                    || discoverInfo.containsFeature(feature));
    }

    /**
     * The current description of the streams that we have going toward the
     * remote side. We use {@link LinkedHashMap}s to make sure that we preserve
     * the order of the individual content extensions.
     */
    private final Map<String, ContentPacketExtension> localContentMap
        = new LinkedHashMap<String, ContentPacketExtension>();

    /**
     * The <tt>QualityControl</tt> of this <tt>CallPeerMediaHandler</tt>.
     */
    private final QualityControlWrapper qualityControls;

    /**
     * The current description of the streams that the remote side has with us.
     * We use {@link LinkedHashMap}s to make sure that we preserve
     * the order of the individual content extensions.
     */
    private final Map<String, ContentPacketExtension> remoteContentMap
        = new LinkedHashMap<String, ContentPacketExtension>();

    /**
     * Indicates whether the remote party has placed us on hold.
     */
    private boolean remotelyOnHold = false;

    /**
     * Whether other party is able to change video quality settings.
     * Normally its whether we have detected existence of imageattr in sdp.
     */
    private boolean supportQualityControls = false;

    /**
     * The <tt>TransportManager</tt> implementation handling our address
     * management.
     */
    private TransportManagerJabberImpl transportManager;

    /**
     * The <tt>Object</tt> which is used for synchronization (e.g. <tt>wait</tt>
     * and <tt>notify</tt>) related to {@link #transportManager}.
     */
    private final Object transportManagerSyncRoot = new Object();

    /**
     * The ordered by preference array of the XML namespaces of the jingle
     * transports that this peer supports. If it is non-null, it will be used
     * instead of checking disco#info in order to select an appropriate
     * transport manager.
     */
    private String[] supportedTransports = null;

    /**
     * Object used to synchronize access to <tt>supportedTransports</tt>
     */
    private final Object supportedTransportsSyncRoot = new Object();

    /**
     * Indicates if the <tt>CallPeer</tt> will support </tt>inputevt</tt>
     * extension (i.e. will be able to be remote-controlled).
     */
    private boolean localInputEvtAware = false;

    /**
     * Creates a new handler that will be managing media streams for
     * <tt>peer</tt>.
     *
     * @param peer that <tt>CallPeerJabberImpl</tt> instance that we will be
     * managing media for.
     */
    public CallPeerMediaHandlerJabberImpl(CallPeerJabberImpl peer)
    {
        super(peer, peer);
        qualityControls = new QualityControlWrapper(peer);
    }

    /**
     * Determines the direction that a stream, which has been placed on
     * hold by the remote party, would need to go back to after being
     * re-activated. If the stream is not currently on hold (i.e. it is still
     * sending media), this method simply returns its current direction.
     *
     * @param stream the {@link MediaStreamTarget} whose post-hold direction
     * we'd like to determine.
     *
     * @return the {@link MediaDirection} that we need to set on <tt>stream</tt>
     * once it is reactivate.
     */
    private MediaDirection calculatePostHoldDirection(MediaStream stream)
    {
        MediaDirection streamDirection = stream.getDirection();

        if (streamDirection.allowsSending())
            return streamDirection;

        /*
         * When calculating a direction we need to take into account 1) what
         * direction the remote party had asked for before putting us on hold,
         * 2) what the user preference is for the stream's media type, 3) our
         * local hold status, 4) the direction supported by the device this
         * stream is reading from.
         */

        // 1. what the remote party originally told us (from our perspective)
        ContentPacketExtension content = remoteContentMap.get(stream.getName());
        MediaDirection postHoldDir
            = JingleUtils.getDirection(content, !getPeer().isInitiator());

        // 2. the user preference
        MediaDevice device = stream.getDevice();

        postHoldDir
            = postHoldDir.and(
                    getDirectionUserPreference(device.getMediaType()));

        // 3. our local hold status
        if (isLocallyOnHold())
            postHoldDir = postHoldDir.and(MediaDirection.SENDONLY);

        // 4. the device direction
        postHoldDir = postHoldDir.and(device.getDirection());

        return postHoldDir;
    }

    /**
     * Closes the <tt>CallPeerMediaHandler</tt>.
     */
    @Override
    public synchronized void close()
    {
        super.close();

        OperationSetDesktopSharingClientJabberImpl client
            = (OperationSetDesktopSharingClientJabberImpl)
                getPeer().getProtocolProvider().getOperationSet(
                    OperationSetDesktopSharingClient.class);

        if (client != null)
            client.fireRemoteControlRevoked(getPeer());
    }

    /**
     * Creates a {@link ContentPacketExtension}s of the streams for a
     * specific <tt>MediaDevice</tt>.
     *
     * @param dev <tt>MediaDevice</tt>
     * @return the {@link ContentPacketExtension}s of stream that this
     * handler is prepared to initiate.
     * @throws OperationFailedException if we fail to create the descriptions
     * for reasons like problems with device interaction, allocating ports, etc.
     */
    private ContentPacketExtension createContent(MediaDevice dev)
        throws OperationFailedException
    {
        MediaType mediaType = dev.getMediaType();
        //this is the direction to be used in the jingle session
        MediaDirection direction = dev.getDirection();
        CallPeerJabberImpl peer = getPeer();

        /*
         * In the case of RTP translation performed by the conference focus,
         * the conference focus is not required to capture media.
         */
        if (!(MediaType.VIDEO.equals(mediaType)
                && isRTPTranslationEnabled(mediaType)))
            direction = direction.and(getDirectionUserPreference(mediaType));

        /*
         * Check if we need to announce sending on behalf of other peers
         */
        CallJabberImpl call = peer.getCall();

        if (call.isConferenceFocus())
        {
            for (CallPeerJabberImpl anotherPeer : call.getCallPeerList())
            {
                if ((anotherPeer != peer)
                        && anotherPeer.getDirection(mediaType)
                                .allowsReceiving())
                {
                    direction = direction.or(MediaDirection.SENDONLY);
                    break;
                }
            }
        }

        if (isLocallyOnHold())
            direction = direction.and(MediaDirection.SENDONLY);

        QualityPreset sendQualityPreset = null;
        QualityPreset receiveQualityPreset = null;

        if(qualityControls != null)
        {
            // the one we will send is the one the remote has announced as
            // receive
            sendQualityPreset = qualityControls.getRemoteReceivePreset();
            // the one we want to receive is the one the remote can send
            receiveQualityPreset = qualityControls.getRemoteSendMaxPreset();
        }
        if(direction != MediaDirection.INACTIVE)
        {
            ContentPacketExtension content
                = createContentForOffer(
                        getLocallySupportedFormats(
                                dev,
                                sendQualityPreset,
                                receiveQualityPreset),
                        direction,
                        dev.getSupportedExtensions());
            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(content);

            // DTLS-SRTP
            setDtlsEncryptionOnContent(mediaType, content, null);

            // SDES
            setSDesEncryptionOnDescription(mediaType, description, null);

            // ZRTP
            setZrtpEncryptionOnDescription(mediaType, description, null);

            return content;
        }
        return null;
    }

    /**
     * Creates a {@link ContentPacketExtension} for a particular stream.
     *
     * @param mediaType <tt>MediaType</tt> of the content
     * @return a {@link ContentPacketExtension}
     * @throws OperationFailedException if we fail to create the descriptions
     * for reasons like - problems with device interaction, allocating ports,
     * etc.
     */
    public ContentPacketExtension createContentForMedia(MediaType mediaType)
        throws OperationFailedException
    {
        MediaDevice dev = getDefaultDevice(mediaType);

        if(isDeviceActive(dev))
            return createContent(dev);
        return null;
    }

    /**
     * Generates an Jingle {@link ContentPacketExtension} for the specified
     * {@link MediaFormat} list, direction and RTP extensions taking account
     * the local streaming preference for the corresponding media type.
     *
     * @param supportedFormats the list of <tt>MediaFormats</tt> that we'd
     * like to advertise.
     * @param direction the <tt>MediaDirection</tt> that we'd like to establish
     * the stream in.
     * @param supportedExtensions the list of <tt>RTPExtension</tt>s that we'd
     * like to advertise in the <tt>MediaDescription</tt>.
     *
     * @return a newly created {@link ContentPacketExtension} representing
     * streams that we'd be able to handle.
     */
    private ContentPacketExtension createContentForOffer(
                                        List<MediaFormat>  supportedFormats,
                                        MediaDirection     direction,
                                        List<RTPExtension> supportedExtensions)
    {
        ContentPacketExtension content
            = JingleUtils.createDescription(
                    ContentPacketExtension.CreatorEnum.initiator,
                    supportedFormats.get(0).getMediaType().toString(),
                    JingleUtils.getSenders(direction, !getPeer().isInitiator()),
                    supportedFormats,
                    supportedExtensions,
                    getDynamicPayloadTypes(),
                    getRtpExtensionsRegistry());

        this.localContentMap.put(content.getName(), content);
        return content;
    }

    /**
     * Creates a <tt>List</tt> containing the {@link ContentPacketExtension}s of
     * the streams that this handler is prepared to initiate depending on
     * available <tt>MediaDevice</tt>s and local on-hold and video transmission
     * preferences.
     *
     * @return a {@link List} containing the {@link ContentPacketExtension}s of
     * streams that this handler is prepared to initiate.
     *
     * @throws OperationFailedException if we fail to create the descriptions
     * for reasons like problems with device interaction, allocating ports, etc.
     */
    public List<ContentPacketExtension> createContentList()
        throws OperationFailedException
    {
        // Describe the media.
        List<ContentPacketExtension> mediaDescs = new ArrayList<>();

        for (MediaType mediaType : MediaType.values())
        {
            MediaDevice dev = getDefaultDevice(mediaType);

            if (isDeviceActive(dev))
            {
                MediaDirection direction = dev.getDirection();

                /*
                 * In the case of RTP translation performed by the conference
                 * focus, the conference focus is not required to capture media.
                 */
                if (!(MediaType.VIDEO.equals(mediaType)
                        && isRTPTranslationEnabled(mediaType)))
                {
                    direction
                        = direction.and(getDirectionUserPreference(mediaType));
                }
                if (isLocallyOnHold())
                    direction = direction.and(MediaDirection.SENDONLY);

                /*
                 * If we're only able to receive, we don't have to offer it at
                 * all. For example, we have to offer audio and no video when we
                 * start an audio call.
                 */
                if (MediaDirection.RECVONLY.equals(direction))
                    direction = MediaDirection.INACTIVE;

                if (direction != MediaDirection.INACTIVE)
                {
                    ContentPacketExtension content
                        = createContentForOffer(
                                getLocallySupportedFormats(dev),
                                direction,
                                dev.getSupportedExtensions());
                    RtpDescriptionPacketExtension description
                        = JingleUtils.getRtpDescription(content);

                    // DTLS-SRTP
                    setDtlsEncryptionOnContent(mediaType, content, null);
                    // SDES
                    setSDesEncryptionOnDescription(
                            mediaType,
                            description,
                            null);
                    //ZRTP
                    setZrtpEncryptionOnDescription(
                            mediaType,
                            description,
                            null);

                    // we request a desktop sharing session so add the inputevt
                    // extension in the "video" content
                    if (description.getMedia().equals(
                                MediaType.VIDEO.toString())
                            && getLocalInputEvtAware())
                    {
                        content.addChildExtension(
                                new InputEvtPacketExtension());
                    }

                    mediaDescs.add(content);
                }
            }
        }

        // Fail if no media is described (e.g. all devices are inactive).
        if (mediaDescs.isEmpty())
        {
            ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                    "We couldn't find any active Audio/Video devices"
                        + " and couldn't create a call",
                    OperationFailedException.GENERAL_ERROR,
                    null,
                    logger);
        }

        // Describe the transport(s).
        return harvestCandidates(null, mediaDescs, null);
    }

    /**
     * Creates a <tt>List</tt> containing the {@link ContentPacketExtension}s of
     * the streams of a specific <tt>MediaType</tt> that this handler is
     * prepared to initiate depending on available <tt>MediaDevice</tt>s and
     * local on-hold and video transmission preferences.
     *
     * @param mediaType <tt>MediaType</tt> of the content
     * @return a {@link List} containing the {@link ContentPacketExtension}s of
     * streams that this handler is prepared to initiate.
     *
     * @throws OperationFailedException if we fail to create the descriptions
     * for reasons like - problems with device interaction, allocating ports,
     * etc.
     */
    public List<ContentPacketExtension> createContentList(MediaType mediaType)
        throws OperationFailedException
    {
        MediaDevice dev = getDefaultDevice(mediaType);
        List<ContentPacketExtension> mediaDescs
            = new ArrayList<ContentPacketExtension>();

        if (isDeviceActive(dev))
        {
            ContentPacketExtension content = createContent(dev);

            if (content != null)
                mediaDescs.add(content);
        }

        // Fail if no media is described (e.g. all devices are inactive).
        if (mediaDescs.isEmpty())
        {
            ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                    "We couldn't find any active Audio/Video devices and "
                        + "couldn't create a call",
                    OperationFailedException.GENERAL_ERROR,
                    null,
                    logger);
        }

        // Describe the transport(s).
        return harvestCandidates(null, mediaDescs, null);
    }

    /**
     * Overrides to give access to the transport manager to send events
     * about ICE state changes.
     *
     * @param property the name of the property of this
     * <tt>PropertyChangeNotifier</tt> which had its value changed
     * @param oldValue the value of the property with the specified name before
     * the change
     * @param newValue the value of the property with the specified name after
     */
    @Override
    protected void firePropertyChange(
            String property,
            Object oldValue, Object newValue)
    {
        super.firePropertyChange(property, oldValue, newValue);
    }

    /**
     * Wraps up any ongoing candidate harvests and returns our response to the
     * last offer we've received, so that the peer could use it to send a
     * <tt>session-accept</tt>.
     *
     * @return  the last generated list of {@link ContentPacketExtension}s that
     * the call peer could use to send a <tt>session-accept</tt>.
     *
     * @throws OperationFailedException if we fail to configure the media stream
     */
    public Iterable<ContentPacketExtension> generateSessionAccept()
        throws OperationFailedException
    {
        TransportManagerJabberImpl transportManager = getTransportManager();
        Iterable<ContentPacketExtension> sessAccept
            = transportManager.wrapupCandidateHarvest();
        CallPeerJabberImpl peer = getPeer();

        //user answered an incoming call so we go through whatever content
        //entries we are initializing and init their corresponding streams

        // First parse content so we know how many streams and what type of
        // content we have
        Map<ContentPacketExtension,RtpDescriptionPacketExtension> contents
            = new HashMap<ContentPacketExtension,RtpDescriptionPacketExtension>();

        for(ContentPacketExtension ourContent : sessAccept)
        {
            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(ourContent);

            contents.put(ourContent, description);
        }

        boolean masterStreamSet = false;

        for(Map.Entry<ContentPacketExtension, RtpDescriptionPacketExtension> en
                : contents.entrySet())
        {
            ContentPacketExtension ourContent = en.getKey();

            RtpDescriptionPacketExtension description = en.getValue();
            MediaType type = MediaType.parseString(description.getMedia());

            // stream connector
            StreamConnector connector
                = transportManager.getStreamConnector(type);

            //the device this stream would be reading from and writing to.
            MediaDevice dev = getDefaultDevice(type);

            if(!isDeviceActive(dev))
                continue;

            // stream target
            MediaStreamTarget target = transportManager.getStreamTarget(type);

            //stream direction
            MediaDirection direction
                = JingleUtils.getDirection(ourContent, !peer.isInitiator());

            // if we answer with video, tell remote peer that video direction is
            // sendrecv, and whether video device can capture/send
            if (MediaType.VIDEO.equals(type)
                    && (isLocalVideoTransmissionEnabled()
                            || isRTPTranslationEnabled(type))
                    && dev.getDirection().allowsSending())
            {
               direction = MediaDirection.SENDRECV;
               ourContent.setSenders(ContentPacketExtension.SendersEnum.both);
            }
            else if (MediaType.AUDIO.equals(type) && !isLocalAudioTransmissionEnabled())
            {
                // there are cases where jigasi would want to not advertise sources and will not be sending anything
                direction = MediaDirection.RECVONLY;
            }

            //let's now see what was the format we announced as first and
            //configure the stream with it.
            String contentName = ourContent.getName();
            ContentPacketExtension theirContent
                = this.remoteContentMap.get(contentName);
            RtpDescriptionPacketExtension theirDescription
                = JingleUtils.getRtpDescription(theirContent);
            MediaFormat format = null;

            List<MediaFormat> localFormats = getLocallySupportedFormats(dev);
            for(PayloadTypePacketExtension payload
                    : theirDescription.getPayloadTypes())
            {
                MediaFormat remoteFormat = JingleUtils.payloadTypeToMediaFormat(
                            payload, getDynamicPayloadTypes());
                if(remoteFormat != null
                     && (format = findMediaFormat(localFormats, remoteFormat))
                            != null)
                    break;
            }

            if(format == null)
            {
                ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                        "No matching codec.",
                        OperationFailedException.ILLEGAL_ARGUMENT,
                        null,
                        logger);
            }

            //extract the extensions that we are advertising:
            // check whether we will be exchanging any RTP extensions.
            List<RTPExtension> rtpExtensions
                = JingleUtils.extractRTPExtensions(
                        description,
                        this.getRtpExtensionsRegistry());

            Map<String, String> adv = format.getAdvancedAttributes();
            if(adv != null)
            {
                for(Map.Entry<String, String> f : adv.entrySet())
                {
                    if(f.getKey().equals("imageattr"))
                        supportQualityControls = true;
                }
            }

            boolean masterStream = false;
            // if we have more than one stream, lets the audio be the master
            if(!masterStreamSet)
            {
                if(contents.size() > 1)
                {
                    if(type.equals(MediaType.AUDIO))
                    {
                        masterStream = true;
                        masterStreamSet = true;
                    }
                }
                else
                {
                    masterStream = true;
                    masterStreamSet = true;
                }
            }

            // create the corresponding stream...
            MediaStream stream = initStream(contentName,
                                            connector,
                                            dev,
                                            format,
                                            target,
                                            direction,
                                            rtpExtensions,
                                            masterStream);

            long ourSsrc = stream.getLocalSourceID();
            if (direction.allowsSending() && ourSsrc != -1)
            {
                description.setSsrc(Long.toString(ourSsrc));
                addSourceExtension(description, ourSsrc);
            }
        }
        return sessAccept;
    }

    /**
     * Adds a <tt>SourcePacketExtension</tt> as a child element of
     * <tt>description</tt>. See XEP-0339.
     *
     * @param description the <tt>RtpDescriptionPacketExtension</tt> to which
     * a child element will be added.
     * @param ssrc the SSRC for the <tt>SourcePacketExtension</tt> to use.
     */
    private void addSourceExtension(RtpDescriptionPacketExtension description,
                                    long ssrc)
    {
        MediaType type = MediaType.parseString(description.getMedia());

        SourcePacketExtension sourcePacketExtension
                = new SourcePacketExtension();

        sourcePacketExtension.setSSRC(ssrc);
        sourcePacketExtension.addChildExtension(
                new ParameterPacketExtension("cname",
                                             LibJitsi.getMediaService()
                                                .getRtpCname()));
        sourcePacketExtension.addChildExtension(
                new ParameterPacketExtension("msid", getMsid(type)));
        sourcePacketExtension.addChildExtension(
                new ParameterPacketExtension("mslabel", getMsLabel()));
        sourcePacketExtension.addChildExtension(
                new ParameterPacketExtension("label", getLabel(type)));

        description.addChildExtension(sourcePacketExtension);
    }

    /**
     * Returns the local content of a specific content type (like audio or
     * video).
     *
     * @param contentType content type name
     * @return remote <tt>ContentPacketExtension</tt> or null if not found
     */
    public ContentPacketExtension getLocalContent(String contentType)
    {
        for(String key : localContentMap.keySet())
        {
            ContentPacketExtension content = localContentMap.get(key);
            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(content);

            if(description.getMedia().equals(contentType))
                return content;
        }
        return null;
    }

    /**
     * Returns a complete list of call currently known local content-s.
     *
     * @return a list of {@link ContentPacketExtension} <tt>null</tt> if not found
     */
    public Iterable<ContentPacketExtension> getLocalContentList()
    {
        return localContentMap.values();
    }

    /**
     * Returns the quality control for video calls if any.
     *
     * @return the implemented quality control.
     */
    public QualityControl getQualityControl()
    {
        if(supportQualityControls)
        {
            return qualityControls;
        }
        else
        {
            // we have detected that its not supported and return null
            // and control ui won't be visible
            return null;
        }
    }

    /**
     * Get the remote content of a specific content type (like audio or video).
     *
     * @param contentType content type name
     * @return remote <tt>ContentPacketExtension</tt> or null if not found
     */
    public ContentPacketExtension getRemoteContent(String contentType)
    {
        for(String key : remoteContentMap.keySet())
        {
            ContentPacketExtension content = remoteContentMap.get(key);
            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(content);

            if(description.getMedia().equals(contentType))
                return content;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRemoteSSRC(MediaType mediaType)
    {
        int[] ssrcs = getRemoteSSRCs(mediaType);

        /*
         * A peer (regardless of whether it is local or remote) may send
         * multiple RTP streams at any time. In such a case, it is not clear
         * which one of their SSRCs is to be returned. Anyway, the super says
         * that the returned is the last known.
         */
        if (ssrcs.length != 0)
            return 0xFFFFFFFFL & ssrcs[ssrcs.length - 1];

        return super.getRemoteSSRC(mediaType);
    }

    /**
     * Gets the SSRCs of RTP streams with a specific <tt>MediaType</tt> known to
     * be received by a <tt>MediaStream</tt> associated with this instance.
     *
     * @param mediaType the <tt>MediaType</tt> of the RTP streams the SSRCs of
     * which are to be returned
     * @return an array of <tt>int</tt> values which represent the SSRCs of RTP
     * streams with the specified <tt>mediaType</tt> known to be received by a
     * <tt>MediaStream</tt> associated with this instance
     */
    private int[] getRemoteSSRCs(MediaType mediaType)
    {
        /*
         * XXX The fallback to the super implementation that follows may lead to
         * unexpected behavior due to the lack of ability to keep track of
         * multiple remote SSRCs.
         */
        long ssrc = super.getRemoteSSRC(mediaType);

        return
            (ssrc == SSRC_UNKNOWN)
                ? new int[0]
                : new int[] { (int) ssrc };
    }

    /**
     * Gets the <tt>TransportManager</tt> implementation handling our address
     * management.
     *
     * TODO: this method can and should be simplified.
     *
     * @return the <tt>TransportManager</tt> implementation handling our address
     * management
     * @see CallPeerMediaHandler#getTransportManager()
     */
    @Override
    protected synchronized TransportManagerJabberImpl getTransportManager()
    {
        if (transportManager == null)
        {
            CallPeerJabberImpl peer = getPeer();

            if (peer.isInitiator())
            {
                synchronized(transportManagerSyncRoot)
                {
                    try
                    {
                        transportManagerSyncRoot.wait(5000);
                    }
                    catch(InterruptedException e)
                    {
                    }
                }
                if(transportManager == null)
                {
                    throw new IllegalStateException(
                            "The initiator is expected to specify the transport"
                                + " in their offer.");
                }
                else
                    return transportManager;
            }
            else
            {
                ProtocolProviderServiceJabberImpl protocolProvider
                    = peer.getProtocolProvider();
                ScServiceDiscoveryManager discoveryManager
                    = protocolProvider.getDiscoveryManager();
                DiscoverInfo peerDiscoverInfo = peer.getDiscoveryInfo();

                /*
                 * If this.supportedTransports has been explicitly set, we use
                 * it to select the transport manager -- we use the first
                 * transport in the list which we recognize (e.g. the first
                 * that is either ice or raw-udp
                 */
                synchronized (supportedTransportsSyncRoot)
                {
                    if (supportedTransports != null
                            && supportedTransports.length > 0)
                    {
                        for(String supportedTransport : supportedTransports)
                        {
                            if (ProtocolProviderServiceJabberImpl.
                                    URN_XMPP_JINGLE_ICE_UDP_1.
                                    equals(supportedTransport))
                            {
                                transportManager = new IceUdpTransportManager(peer);
                                break;
                            }
                            else if (ProtocolProviderServiceJabberImpl.
                                    URN_XMPP_JINGLE_RAW_UDP_0.
                                    equals(supportedTransport))
                            {
                                transportManager
                                        = new RawUdpTransportManager(peer);
                                break;
                            }
                        }
                        if (transportManager == null)
                        {
                            logger.warn(
                                    "Could not find a supported"
                                        + " TransportManager in"
                                        + " supportedTransports. Will try to"
                                        + " select one based on disco#info.");
                        }
                    }
                }

                if (transportManager == null)
                {
                    /*
                     * The list of possible transports ordered by decreasing
                     * preference.
                     */
                    String[] transports
                        = new String[]
                                {
                                    ProtocolProviderServiceJabberImpl
                                        .URN_XMPP_JINGLE_ICE_UDP_1,
                                    ProtocolProviderServiceJabberImpl
                                        .URN_XMPP_JINGLE_RAW_UDP_0
                                };

                    /*
                     * Select the first transport from the list of possible
                     * transports ordered by decreasing preference which is
                     * supported by the local and the remote peers.
                     */
                    for (String transport : transports)
                    {
                        if (isFeatureSupported(
                                discoveryManager,
                                peerDiscoverInfo,
                                transport))
                        {
                            if (ProtocolProviderServiceJabberImpl
                                    .URN_XMPP_JINGLE_ICE_UDP_1
                                        .equals(transport))
                            {
                                transportManager
                                    = new IceUdpTransportManager(peer);
                            }
                            else if (ProtocolProviderServiceJabberImpl
                                    .URN_XMPP_JINGLE_RAW_UDP_0
                                        .equals(transport))
                            {
                                transportManager
                                    = new RawUdpTransportManager(peer);
                            }

                            if (transportManager != null)
                                break;
                        }
                    }

                    if ((transportManager == null) && logger.isDebugEnabled())
                    {
                        logger.debug(
                                "No known Jingle transport supported by Jabber"
                                    + " call peer " + peer);
                    }
                }
            }
        }
        return transportManager;
    }

    /**
     * {@inheritDoc}
     *
     * @see CallPeerMediaHandler#queryTransportManager()
     */
    @Override
    protected synchronized TransportManagerJabberImpl queryTransportManager()
    {
        return transportManager;
    }

    /**
     * Gathers local candidate addresses.
     *
     * @param remote the media descriptions received from the remote peer if any
     * or <tt>null</tt> if <tt>local</tt> represents an offer from the local
     * peer to be sent to the remote peer
     * @param local the media descriptions sent or to be sent from the local
     * peer to the remote peer. If <tt>remote</tt> is <tt>null</tt>,
     * <tt>local</tt> represents an offer from the local peer to be sent to the
     * remote peer
     * @param transportInfoSender the <tt>TransportInfoSender</tt> to be used by
     * this <tt>TransportManagerJabberImpl</tt> to send <tt>transport-info</tt>
     * <tt>JingleIQ</tt>s from the local peer to the remote peer if this
     * <tt>TransportManagerJabberImpl</tt> wishes to utilize
     * <tt>transport-info</tt>
     * @return the media descriptions of the local peer after the local
     * candidate addresses have been gathered as returned by
     * {@link TransportManagerJabberImpl#wrapupCandidateHarvest()}
     * @throws OperationFailedException if anything goes wrong while starting or
     * wrapping up the gathering of local candidate addresses
     */
    private List<ContentPacketExtension> harvestCandidates(
            List<ContentPacketExtension> remote,
            List<ContentPacketExtension> local,
            TransportInfoSender transportInfoSender)
        throws OperationFailedException
    {
        long startCandidateHarvestTime = System.currentTimeMillis();
        TransportManagerJabberImpl transportManager = getTransportManager();

        if (remote == null)
        {
            /*
             * We'll be harvesting candidates in order to make an offer so it
             * doesn't make sense to send them in transport-info.
             */
            if (transportInfoSender != null)
                throw new IllegalArgumentException("transportInfoSender");

            transportManager.startCandidateHarvest(local, transportInfoSender);
        }
        else
        {
            transportManager.startCandidateHarvest(
                    remote,
                    local,
                    transportInfoSender);
        }

        long stopCandidateHarvestTime = System.currentTimeMillis();

        if (logger.isInfoEnabled())
        {
            long candidateHarvestTime
                = stopCandidateHarvestTime - startCandidateHarvestTime;

            logger.info(
                    "End candidate harvest within " + candidateHarvestTime
                        + " ms");
        }

        setDtlsEncryptionOnTransports(remote, local);

        /*
         * TODO Ideally, we wouldn't wrap up that quickly. We need to revisit
         * this.
         */
        return transportManager.wrapupCandidateHarvest();
    }

    /**
     * Creates if necessary, and configures the stream that this
     * <tt>MediaHandler</tt> is using for the <tt>MediaType</tt> matching the
     * one of the <tt>MediaDevice</tt>. This method extends the one already
     * available by adding a stream name, corresponding to a stream's content
     * name.
     *
     * @param streamName the name of the stream as indicated in the XMPP
     * <tt>content</tt> element.
     * @param connector the <tt>MediaConnector</tt> that we'd like to bind the
     * newly created stream to.
     * @param device the <tt>MediaDevice</tt> that we'd like to attach the newly
     * created <tt>MediaStream</tt> to.
     * @param format the <tt>MediaFormat</tt> that we'd like the new
     * <tt>MediaStream</tt> to be set to transmit in.
     * @param target the <tt>MediaStreamTarget</tt> containing the RTP and RTCP
     * address:port couples that the new stream would be sending packets to.
     * @param direction the <tt>MediaDirection</tt> that we'd like the new
     * stream to use (i.e. sendonly, sendrecv, recvonly, or inactive).
     * @param rtpExtensions the list of <tt>RTPExtension</tt>s that should be
     * enabled for this stream.
     * @param masterStream whether the stream to be used as master if secured
     *
     * @return the newly created <tt>MediaStream</tt>.
     *
     * @throws OperationFailedException if creating the stream fails for any
     * reason (like for example accessing the device or setting the format).
     */
    protected MediaStream initStream(String               streamName,
                                     StreamConnector      connector,
                                     MediaDevice          device,
                                     MediaFormat          format,
                                     MediaStreamTarget    target,
                                     MediaDirection       direction,
                                     List<RTPExtension>   rtpExtensions,
                                     boolean              masterStream)
        throws OperationFailedException
    {
        MediaStream stream
            = super.initStream(
                    connector,
                    device,
                    format,
                    target,
                    direction,
                    rtpExtensions,
                    masterStream);

        if (stream != null)
            stream.setName(streamName);

        return stream;
    }

    /**
     * Handles the specified <tt>answer</tt> by creating and initializing the
     * corresponding <tt>MediaStream</tt>s.
     *
     * @param answer the Jingle answer
     *
     * @throws OperationFailedException if we fail to handle <tt>answer</tt> for
     * reasons like failing to initialize media devices or streams.
     * @throws IllegalArgumentException if there's a problem with the syntax or
     * the semantics of <tt>answer</tt>. Method is synchronized in order to
     * avoid closing mediaHandler when we are currently in process of
     * initializing, configuring and starting streams and anybody interested
     * in this operation can synchronize to the mediaHandler instance to wait
     * processing to stop (method setState in CallPeer).
     */
    public void processAnswer(List<ContentPacketExtension> answer)
        throws OperationFailedException,
               IllegalArgumentException
    {
        /*
         * The answer given in session-accept may contain transport-related
         * information compatible with that carried in transport-info.
         */
        processTransportInfo(answer);

        boolean masterStreamSet = false;

        for (ContentPacketExtension content : answer)
        {
            remoteContentMap.put(content.getName(), content);

            boolean masterStream = false;

            // if we have more than one stream, let the audio be the master
            if(!masterStreamSet)
            {
                if(answer.size() > 1)
                {
                    RtpDescriptionPacketExtension description
                        = JingleUtils.getRtpDescription(content);

                    if(MediaType.AUDIO.toString().equals(
                            description.getMedia()))
                    {
                        masterStream = true;
                        masterStreamSet = true;
                    }
                }
                else
                {
                    masterStream = true;
                    masterStreamSet = true;
                }
            }

            processContent(content, false, masterStream);
        }
    }

    /**
     * Process a <tt>ContentPacketExtension</tt> and initialize its
     * corresponding <tt>MediaStream</tt>.
     *
     * @param content a <tt>ContentPacketExtension</tt>
     * @param modify if it correspond to a content-modify for resolution change
     * @param masterStream whether the stream to be used as master
     * @throws OperationFailedException if we fail to handle <tt>content</tt>
     * for reasons like failing to initialize media devices or streams.
     * @throws IllegalArgumentException if there's a problem with the syntax or
     * the semantics of <tt>content</tt>. The method is synchronized in order to
     * avoid closing mediaHandler when we are currently in process of
     * initializing, configuring and starting streams and anybody interested
     * in this operation can synchronize to the mediaHandler instance to wait
     * processing to stop (method setState in CallPeer).
     */
    private void processContent(
            ContentPacketExtension content,
            boolean modify,
            boolean masterStream)
        throws OperationFailedException,
               IllegalArgumentException
    {
        RtpDescriptionPacketExtension description
            = JingleUtils.getRtpDescription(content);
        MediaType mediaType = JingleUtils.getMediaType(content);

        //stream target
        TransportManagerJabberImpl transportManager = getTransportManager();
        MediaStreamTarget target = transportManager.getStreamTarget(mediaType);

        if (target == null)
            target = JingleUtils.extractDefaultTarget(content);

        // no target port - try next media description
        if((target == null) || (target.getDataAddress().getPort() == 0))
        {
            closeStream(mediaType);
            return;
        }

        List<MediaFormat> supportedFormats = JingleUtils.extractFormats(
            description, getDynamicPayloadTypes());

        MediaDevice dev = getDefaultDevice(mediaType);

        if(!isDeviceActive(dev))
        {
            closeStream(mediaType);
            return;
        }

        MediaDirection devDirection
            = (dev == null) ? MediaDirection.INACTIVE : dev.getDirection();

        // Take the preference of the user with respect to streaming
        // mediaType into account.
        devDirection
            = devDirection.and(getDirectionUserPreference(mediaType));

        if (supportedFormats.isEmpty())
        {
            //remote party must have messed up our Jingle description.
            //throw an exception.
            ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                    "Remote party sent an invalid Jingle answer.",
                    OperationFailedException.ILLEGAL_ARGUMENT,
                    null,
                    logger);
        }

        CallJabberImpl call = getPeer().getCall();
        CallConference conference
            = (call == null) ? null : call.getConference();

        addZrtpAdvertisedEncryptions(true, description, mediaType);
        addSDesAdvertisedEncryptions(true, description, mediaType);
        addDtlsAdvertisedEncryptions(true, content, mediaType, false);

        StreamConnector connector
            = transportManager.getStreamConnector(mediaType);

        //determine the direction that we need to announce.
        MediaDirection remoteDirection
            = JingleUtils.getDirection(content, getPeer().isInitiator());
        /*
         * If we are the focus of a conference, we need to take into account the
         * other participants.
         */
        if ((conference != null) && conference.isConferenceFocus())
        {
            for (CallPeerJabberImpl peer : call.getCallPeerList())
            {
                SendersEnum senders = peer.getSenders(mediaType);
                boolean initiator = peer.isInitiator();
                //check if the direction of the jingle session we have with
                //this peer allows us receiving media. If senders is null,
                //assume the default of 'both'
                if ((senders == null)
                        || (SendersEnum.both == senders)
                        || (initiator && SendersEnum.initiator == senders)
                        || (!initiator && SendersEnum.responder == senders))
                {
                    remoteDirection
                        = remoteDirection.or(MediaDirection.SENDONLY);
                }
            }
        }

        MediaDirection direction
            = devDirection.getDirectionForAnswer(remoteDirection);

        // update the RTP extensions that we will be exchanging.
        List<RTPExtension> remoteRTPExtensions
            = JingleUtils.extractRTPExtensions(
                    description,
                    getRtpExtensionsRegistry());
        List<RTPExtension> supportedExtensions
            = getExtensionsForType(mediaType);
        List<RTPExtension> rtpExtensions
            = intersectRTPExtensions(remoteRTPExtensions, supportedExtensions);

        Map<String, String> adv
            = supportedFormats.get(0).getAdvancedAttributes();

        if(adv != null)
        {
            for(Map.Entry<String, String> f : adv.entrySet())
            {
                if(f.getKey().equals("imageattr"))
                    supportQualityControls = true;
            }
        }

        // check for options from remote party and set them locally
        if(mediaType.equals(MediaType.VIDEO) && modify)
        {
            // update stream
            MediaStream stream = getStream(MediaType.VIDEO);

            if(stream != null && dev != null)
            {
                List<MediaFormat> fmts = supportedFormats;

                if(!fmts.isEmpty())
                {
                    MediaFormat fmt = fmts.get(0);

                    ((VideoMediaStream)stream).updateQualityControl(
                            fmt.getAdvancedAttributes());
                }
            }

            if(qualityControls != null)
            {
                QualityPreset receiveQualityPreset
                    = qualityControls.getRemoteReceivePreset();
                QualityPreset sendQualityPreset
                    = qualityControls.getRemoteSendMaxPreset();

                supportedFormats
                    = (dev == null)
                        ? null
                        : intersectFormats(
                                supportedFormats,
                                getLocallySupportedFormats(
                                        dev,
                                        sendQualityPreset,
                                        receiveQualityPreset));
            }
        }

        // create the corresponding stream...
        initStream(
                content.getName(),
                connector,
                dev,
                supportedFormats.get(0),
                target,
                direction,
                rtpExtensions,
                masterStream);
    }

    /**
     * Parses and handles the specified <tt>offer</tt> and returns a content
     * extension representing the current state of this media handler. This
     * method MUST only be called when <tt>offer</tt> is the first session
     * description that this <tt>MediaHandler</tt> is seeing.
     *
     * @param offer the offer that we'd like to parse, handle and get an answer
     * for.
     *
     * @throws OperationFailedException if we have a problem satisfying the
     * description received in <tt>offer</tt> (e.g. failed to open a device or
     * initialize a stream ...).
     * @throws IllegalArgumentException if there's a problem with
     * <tt>offer</tt>'s format or semantics.
     */
    public void processOffer(List<ContentPacketExtension> offer)
        throws OperationFailedException,
               IllegalArgumentException
    {
        // prepare to generate answers to all the incoming descriptions
        List<ContentPacketExtension> answer = new ArrayList<>(offer.size());
        boolean atLeastOneValidDescription = false;

        for (ContentPacketExtension content : offer)
        {
            remoteContentMap.put(content.getName(), content);

            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(content);
            MediaType mediaType
                = JingleUtils.getMediaType(content);

            List<MediaFormat> remoteFormats
                = JingleUtils.extractFormats(
                        description,
                        getDynamicPayloadTypes());

            MediaDevice dev = getDefaultDevice(mediaType);

            MediaDirection devDirection
                = (dev == null) ? MediaDirection.INACTIVE : dev.getDirection();

            // Take the preference of the user with respect to streaming
            // mediaType into account.
            devDirection
                = devDirection.and(getDirectionUserPreference(mediaType));

            // determine the direction that we need to announce.
            MediaDirection remoteDirection = JingleUtils.getDirection(
                                            content, getPeer().isInitiator());
            MediaDirection direction
                = devDirection.getDirectionForAnswer(remoteDirection);

            // intersect the MediaFormats of our device with remote ones
            List<MediaFormat> mutuallySupportedFormats
                = intersectFormats(
                        remoteFormats,
                        getLocallySupportedFormats(dev));

            // check whether we will be exchanging any RTP extensions.
            List<RTPExtension> offeredRTPExtensions
                    = JingleUtils.extractRTPExtensions(
                            description, this.getRtpExtensionsRegistry());

            List<RTPExtension> supportedExtensions
                    = getExtensionsForType(mediaType);

            List<RTPExtension> rtpExtensions = intersectRTPExtensions(
                            offeredRTPExtensions, supportedExtensions);

            // transport
            /*
             * RawUdpTransportPacketExtension extends
             * IceUdpTransportPacketExtension so getting
             * IceUdpTransportPacketExtension should suffice.
             */
            IceUdpTransportPacketExtension transport
                = content.getFirstChildOfType(
                        IceUdpTransportPacketExtension.class);

            // stream target
            MediaStreamTarget target = null;

            try
            {
                target = JingleUtils.extractDefaultTarget(content);
            }
            catch(IllegalArgumentException e)
            {
                logger.warn("Fail to extract default target", e);
            }

            // according to XEP-176, transport element in session-initiate
            // "MAY instead be empty (with each candidate to be sent as the
            // payload of a transport-info message)".
            int targetDataPort
                = (target == null && transport != null)
                    ? -1
                    : (target != null) ? target.getDataAddress().getPort() : 0;

            /*
             * TODO If the offered transport is not supported, attempt to fall
             * back to a supported one using transport-replace.
             */
            setTransportManager(transport.getNamespace());

            boolean rtcpmux = false;
            if (!transport.getChildExtensionsOfType(
                    IceRtcpmuxPacketExtension.class).isEmpty())
            {
                rtcpmux = true;
                getTransportManager().setRtcpmux(true);
            }

            if (mutuallySupportedFormats.isEmpty()
                    || (devDirection == MediaDirection.INACTIVE)
                    || (targetDataPort == 0))
            {
                // skip stream and continue. contrary to sip we don't seem to
                // need to send per-stream disabling answer and only one at the
                // end.

                //close the stream in case it already exists
                closeStream(mediaType);
                continue;
            }

            SendersEnum senders = JingleUtils.getSenders(
                    direction,
                    !getPeer().isInitiator());
            // create the answer description
            ContentPacketExtension ourContent
                = JingleUtils.createDescription(
                        content.getCreator(),
                        content.getName(),
                        senders,
                        mutuallySupportedFormats,
                        rtpExtensions,
                        getDynamicPayloadTypes(),
                        getRtpExtensionsRegistry());

            /*
             * Sets ZRTP, SDES or DTLS-SRTP depending on the preferences for
             * this account.
             */
            setAndAddPreferredEncryptionProtocol(
                    mediaType,
                    ourContent,
                    content,
                    rtcpmux);

            // Got a content which has inputevt. It means that the peer requests
            // a desktop sharing session so tell it we support inputevt.
            if(content.getChildExtensionsOfType(InputEvtPacketExtension.class)
                    != null)
            {
                ourContent.addChildExtension(new InputEvtPacketExtension());
            }

            answer.add(ourContent);
            localContentMap.put(content.getName(), ourContent);

            atLeastOneValidDescription = true;
        }

        if (!atLeastOneValidDescription)
        {
            ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                    "Offer contained no media formats"
                        + " or no valid media descriptions.",
                    OperationFailedException.ILLEGAL_ARGUMENT,
                    null,
                    logger);
        }

        TransportInfoSender infoSender = new TransportInfoSender()
        {
            @Override
            public void sendTransportInfo(
                Iterable<ContentPacketExtension> contents)
            {
                try
                {
                    getPeer().sendTransportInfo(contents);
                }
                catch (NotConnectedException | InterruptedException e)
                {
                    logger.error("Could not send transport info", e);
                }
            }
        };

        /*
         * In order to minimize post-pickup delay, start establishing the
         * connectivity prior to ringing.
         */
        harvestCandidates(offer, answer, infoSender);

        /*
         * While it may sound like we can completely eliminate the post-pickup
         * delay by waiting for the connectivity establishment to finish, it may
         * not be possible in all cases. We are the Jingle session responder so,
         * in the case of the ICE UDP transport, we are not the controlling ICE
         * Agent and we cannot be sure when the controlling ICE Agent will
         * perform the nomination. It could, for example, choose to wait for our
         * session-accept to perform the nomination which will deadlock us if we
         * have chosen to wait for the connectivity establishment to finish
         * before we begin ringing and send session-accept.
         */
        getTransportManager().startConnectivityEstablishment(offer);
    }

    /**
     * Processes the transport-related information provided by the remote
     * <tt>peer</tt> in a specific set of <tt>ContentPacketExtension</tt>s.
     *
     * @param contents the <tt>ContentPacketExtenion</tt>s provided by the
     * remote <tt>peer</tt> and containing the transport-related information to
     * be processed
     * @throws OperationFailedException if anything goes wrong while processing
     * the transport-related information provided by the remote <tt>peer</tt> in
     * the specified set of <tt>ContentPacketExtension</tt>s
     */
    public void processTransportInfo(Iterable<ContentPacketExtension> contents)
        throws OperationFailedException
    {
        if (getTransportManager().startConnectivityEstablishment(contents))
        {
            //Emil: why the heck is this here and why is it commented?
            //wrapupConnectivityEstablishment();
        }
    }

    /**
     * Reinitialize all media contents.
     *
     * @throws OperationFailedException if we fail to handle <tt>content</tt>
     * for reasons like failing to initialize media devices or streams.
     * @throws IllegalArgumentException if there's a problem with the syntax or
     * the semantics of <tt>content</tt>. Method is synchronized in order to
     * avoid closing mediaHandler when we are currently in process of
     * initializing, configuring and starting streams and anybody interested
     * in this operation can synchronize to the mediaHandler instance to wait
     * processing to stop (method setState in CallPeer).
     */
    public void reinitAllContents()
        throws OperationFailedException,
               IllegalArgumentException
    {
        boolean masterStreamSet = false;
        for(String key : remoteContentMap.keySet())
        {
            ContentPacketExtension ext = remoteContentMap.get(key);

            boolean masterStream = false;
            // if we have more than one stream, lets the audio be the master
            if(!masterStreamSet)
            {
                RtpDescriptionPacketExtension description
                    = JingleUtils.getRtpDescription(ext);
                MediaType mediaType
                    = MediaType.parseString( description.getMedia() );

                if(remoteContentMap.size() > 1)
                {
                    if(mediaType.equals(MediaType.AUDIO))
                    {
                        masterStream = true;
                        masterStreamSet = true;
                    }
                }
                else
                {
                    masterStream = true;
                    masterStreamSet = true;
                }
            }

            if(ext != null)
                processContent(ext, false, masterStream);
        }
    }

    /**
     * Reinitialize a media content such as video.
     *
     * @param name name of the Jingle content
     * @param content media content
     * @param modify if it correspond to a content-modify for resolution change
     * @throws OperationFailedException if we fail to handle <tt>content</tt>
     * for reasons like failing to initialize media devices or streams.
     * @throws IllegalArgumentException if there's a problem with the syntax or
     * the semantics of <tt>content</tt>. Method is synchronized in order to
     * avoid closing mediaHandler when we are currently in process of
     * initializing, configuring and starting streams and anybody interested
     * in this operation can synchronize to the mediaHandler instance to wait
     * processing to stop (method setState in CallPeer).
     */
    public void reinitContent(
            String name,
            ContentPacketExtension content,
            boolean modify)
        throws OperationFailedException,
               IllegalArgumentException
    {
        ContentPacketExtension ext = remoteContentMap.get(name);

        if(ext != null)
        {
            if(modify)
            {
                processContent(content, modify, false);
                remoteContentMap.put(name, content);
            }
            else
            {
                ext.setSenders(content.getSenders());
                processContent(ext, modify, false);
                remoteContentMap.put(name, ext);
            }
        }
    }

    /**
     * Removes a media content with a specific name from the session represented
     * by this <tt>CallPeerMediaHandlerJabberImpl</tt> and closes its associated
     * media stream.
     *
     * @param contentMap the <tt>Map</tt> in which the specified <tt>name</tt>
     * has an association with the media content to be removed
     * @param name the name of the media content to be removed from this session
     */
    private void removeContent(
            Map<String, ContentPacketExtension> contentMap,
            String name)
    {
        ContentPacketExtension content = contentMap.remove(name);

        if (content != null)
        {
            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(content);
            String media = description.getMedia();

            if (media != null)
                closeStream(MediaType.parseString(media));
        }
    }

    /**
     * Removes a media content with a specific name from the session represented
     * by this <tt>CallPeerMediaHandlerJabberImpl</tt> and closes its associated
     * media stream.
     *
     * @param name the name of the media content to be removed from this session
     */
    public void removeContent(String name)
    {
        removeContent(localContentMap, name);
        removeContent(remoteContentMap, name);

        TransportManagerJabberImpl transportManager =  queryTransportManager();

        if (transportManager != null)
            transportManager.removeContent(name);
    }

    /**
     * Acts upon a notification received from the remote party indicating that
     * they've put us on/off hold.
     *
     * @param onHold <tt>true</tt> if the remote party has put us on hold
     * and <tt>false</tt> if they've just put us off hold.
     */
    public void setRemotelyOnHold(boolean onHold)
        throws NotConnectedException, InterruptedException
    {
        this.remotelyOnHold = onHold;

        for (MediaType mediaType : MediaType.values())
        {
            MediaStream stream = getStream(mediaType);

            if (stream == null)
                continue;

            if (remotelyOnHold)
            {
                /*
                 * In conferences we use INACTIVE to prevent, for example,
                 * on-hold music from being played to all the participants.
                 */
                MediaDirection newDirection
                    = getPeer().getCall().isConferenceFocus()
                        ? MediaDirection.INACTIVE
                        : stream.getDirection().and(
                                MediaDirection.RECVONLY);

                stream.setDirection(newDirection);
            }
            else
            {
                stream.setDirection(calculatePostHoldDirection(stream));
            }
        }
    }

    /**
     * Sometimes as initing a call with custom preset can set and we force
     * that quality controls is supported.
     *
     * @param value whether quality controls is supported..
     */
    public void setSupportQualityControls(boolean value)
    {
        this.supportQualityControls = value;
    }

    /**
     * Sets the <tt>TransportManager</tt> implementation to handle our address
     * management by Jingle transport XML namespace.
     *
     * @param xmlns the Jingle transport XML namespace specifying the
     * <tt>TransportManager</tt> implementation type to be set on this instance
     * to handle our address management
     * @throws IllegalArgumentException if the specified <tt>xmlns</tt> does not
     * specify a (supported) <tt>TransportManager</tt> implementation type
     */
    private void setTransportManager(String xmlns)
        throws IllegalArgumentException
    {
        // Is this really going to be an actual change?
        if ((transportManager != null)
                && transportManager.getXmlNamespace().equals(xmlns))
        {
            return;
        }

        CallPeerJabberImpl peer = getPeer();

        if (!peer.getProtocolProvider().getDiscoveryManager().includesFeature(
                xmlns))
        {
            throw new IllegalArgumentException(
                    "Unsupported Jingle transport " + xmlns);
        }

        /*
         * TODO The transportManager is going to be changed so it may need to be
         * disposed of prior to the change.
         */
        switch (xmlns)
        {
            case ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_ICE_UDP_1:
                transportManager = new IceUdpTransportManager(peer);
                break;
            case ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_RAW_UDP_0:
                transportManager = new RawUdpTransportManager(peer);
                break;
            default:
                throw new IllegalArgumentException("Unsupported Jingle " +
                        "transport " + xmlns);
        }

        synchronized(transportManagerSyncRoot)
        {
            transportManagerSyncRoot.notify();
        }
    }

    /**
     * Waits for the associated <tt>TransportManagerJabberImpl</tt> to conclude
     * any started connectivity establishment and then starts this
     * <tt>CallPeerMediaHandler</tt>.
     *
     * @throws IllegalStateException if no offer or answer has been provided or
     * generated earlier
     */
    @Override
    public void start()
        throws IllegalStateException
    {
        try
        {
            wrapupConnectivityEstablishment();
        }
        catch (OperationFailedException ofe)
        {
            throw new UndeclaredThrowableException(ofe);
        }

        super.start();
    }

    /**
     * Lets the underlying implementation take note of this error and only
     * then throws it to the using bundles.
     *
     * @param message the message to be logged and then wrapped in a new
     * <tt>OperationFailedException</tt>
     * @param errorCode the error code to be assigned to the new
     * <tt>OperationFailedException</tt>
     * @param cause the <tt>Throwable</tt> that has caused the necessity to log
     * an error and have a new <tt>OperationFailedException</tt> thrown
     *
     * @throws OperationFailedException the exception that we wanted this method
     * to throw.
     */
    @Override
    protected void throwOperationFailedException(
            String message,
            int errorCode,
            Throwable cause)
        throws OperationFailedException
    {
        ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                message,
                errorCode,
                cause,
                logger);
    }

    /**
     * Notifies the associated <tt>TransportManagerJabberImpl</tt> that it
     * should conclude any connectivity establishment, waits for it to actually
     * do so and sets the <tt>connector</tt>s and <tt>target</tt>s of the
     * <tt>MediaStream</tt>s managed by this <tt>CallPeerMediaHandler</tt>.
     *
     * @throws OperationFailedException if anything goes wrong while setting the
     * <tt>connector</tt>s and/or <tt>target</tt>s of the <tt>MediaStream</tt>s
     * managed by this <tt>CallPeerMediaHandler</tt>
     */
    private void wrapupConnectivityEstablishment()
        throws OperationFailedException
    {
        TransportManagerJabberImpl transportManager = getTransportManager();

        transportManager.wrapupConnectivityEstablishment();
        for (MediaType mediaType : MediaType.values())
        {
            MediaStream stream = getStream(mediaType);

            if (stream != null)
            {
                stream.setConnector(
                        transportManager.getStreamConnector(mediaType));
                stream.setTarget(transportManager.getStreamTarget(mediaType));
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * The super implementation relies on the direction of the streams and is
     * therefore not accurate when we use a Videobridge.
     */
    @Override
    public boolean isRemotelyOnHold()
    {
        return remotelyOnHold;
    }

    /**
     * Detects and adds DTLS-SRTP available encryption method present in the
     * content (description) given in parameter.
     *
     * @param isInitiator <tt>true</tt> if the local call instance is the
     * initiator of the call; <tt>false</tt>, otherwise.
     * @param content The CONTENT element of the JINGLE element which contains
     * the TRANSPORT element
     * @param mediaType The type of media (AUDIO or VIDEO).
     */
    private boolean addDtlsAdvertisedEncryptions(
            boolean isInitiator,
            ContentPacketExtension content,
            MediaType mediaType,
            boolean rtcpmux)
    {
        IceUdpTransportPacketExtension remoteTransport
            = content.getFirstChildOfType(IceUdpTransportPacketExtension.class);

        return
            addDtlsAdvertisedEncryptions(
                    isInitiator,
                    remoteTransport,
                    mediaType,
                    rtcpmux);
    }

    /**
     * Detects and adds DTLS-SRTP available encryption method present in the
     * transport (description) given in parameter.
     *
     * @param isInitiator <tt>true</tt> if the local call instance is the
     * initiator of the call; <tt>false</tt>, otherwise.
     * @param remoteTransport the TRANSPORT element
     * @param mediaType The type of media (AUDIO or VIDEO).
     */
    boolean addDtlsAdvertisedEncryptions(
            boolean isInitiator,
            IceUdpTransportPacketExtension remoteTransport,
            MediaType mediaType,
            boolean rtcpmux)
    {
        SrtpControls srtpControls = getSrtpControls();
        boolean b = false;

        if (remoteTransport != null)
        {
            List<DtlsFingerprintPacketExtension> remoteFingerpintPEs
                = remoteTransport.getChildExtensionsOfType(
                        DtlsFingerprintPacketExtension.class);

            if (!remoteFingerpintPEs.isEmpty())
            {
                AccountID accountID
                    = getPeer().getProtocolProvider().getAccountID();

                if (accountID.getAccountPropertyBoolean(
                            ProtocolProviderFactory.DEFAULT_ENCRYPTION,
                            true)
                        && accountID.isEncryptionProtocolEnabled(
                                SrtpControlType.DTLS_SRTP))
                {
                    Map<String,String> remoteFingerprints
                        = new LinkedHashMap<String,String>();

                    for (DtlsFingerprintPacketExtension remoteFingerprintPE
                            : remoteFingerpintPEs)
                    {
                        String remoteFingerprint
                            = remoteFingerprintPE.getFingerprint();
                        String remoteHash = remoteFingerprintPE.getHash();

                        remoteFingerprints.put(
                                remoteHash,
                                remoteFingerprint);
                    }

                    DtlsControl dtlsControl;
                    DtlsControl.Setup setup;

                    // TODO Read the setup from the remote DTLS fingerprint
                    // packet extension.
                    if (isInitiator)
                    {
                        dtlsControl
                            = (DtlsControl)
                                srtpControls.get(
                                        mediaType,
                                        SrtpControlType.DTLS_SRTP);
                        setup = DtlsControl.Setup.PASSIVE;
                    }
                    else
                    {
                        dtlsControl
                            = (DtlsControl)
                                srtpControls.getOrCreate(
                                        mediaType,
                                        SrtpControlType.DTLS_SRTP);
                        setup = DtlsControl.Setup.ACTIVE;
                    }
                    if (dtlsControl != null)
                    {
                        dtlsControl.setRemoteFingerprints(remoteFingerprints);
                        dtlsControl.setSetup(setup);
                        if (rtcpmux)
                        {
                            dtlsControl.setRtcpmux(true);
                        }
                        removeAndCleanupOtherSrtpControls(
                                mediaType,
                                SrtpControlType.DTLS_SRTP);
                        addAdvertisedEncryptionMethod(
                                SrtpControlType.DTLS_SRTP);
                        b = true;
                    }
                }
            }
        }
        /*
         * If they haven't advertised DTLS-SRTP in their (media) description,
         * then DTLS-SRTP shouldn't be functioning as far as we're concerned.
         */
        if (!b)
        {
            SrtpControl dtlsControl
                = srtpControls.get(mediaType, SrtpControlType.DTLS_SRTP);

            if (dtlsControl != null)
            {
                srtpControls.remove(mediaType, SrtpControlType.DTLS_SRTP);
                dtlsControl.cleanup(null);
            }
        }
        return b;
    }

    /**
     * Selects the preferred encryption protocol (only used by the callee).
     *
     * @param mediaType The type of media (AUDIO or VIDEO).
     * @param localContent The element containing the media DESCRIPTION and
     * its encryption.
     * @param remoteContent The element containing the media DESCRIPTION and
     * its encryption for the remote peer; <tt>null</tt> if the local peer is
     * the initiator of the call.
     */
    private void setAndAddPreferredEncryptionProtocol(
            MediaType mediaType,
            ContentPacketExtension localContent,
            ContentPacketExtension remoteContent,
            boolean rtcpmux)
    {
        List<SrtpControlType> preferredEncryptionProtocols
            = getPeer()
                .getProtocolProvider()
                    .getAccountID()
                        .getSortedEnabledEncryptionProtocolList();

        for (SrtpControlType srtpControlType : preferredEncryptionProtocols)
        {
            // DTLS-SRTP
            if (srtpControlType == SrtpControlType.DTLS_SRTP)
            {
                addDtlsAdvertisedEncryptions(
                        false,
                        remoteContent,
                        mediaType,
                        rtcpmux);

                if (setDtlsEncryptionOnContent(
                        mediaType,
                        localContent,
                        remoteContent))
                {
                    // Stop once an encryption advertisement has been chosen.
                    return;
                }
            }
            else
            {
                RtpDescriptionPacketExtension localDescription
                    = (localContent == null)
                        ? null
                        : JingleUtils.getRtpDescription(localContent);
                RtpDescriptionPacketExtension remoteDescription
                    = (remoteContent == null)
                        ? null
                        : JingleUtils.getRtpDescription(remoteContent);

                if (setAndAddPreferredEncryptionProtocol(
                        srtpControlType,
                        mediaType,
                        localDescription,
                        remoteDescription))
                {
                    // Stop once an encryption advertisement has been chosen.
                    return;
                }
            }
        }
    }

    /**
     * Sets DTLS-SRTP element(s) to the TRANSPORT element of the CONTENT for a
     * given media.
     *
     * @param mediaType The type of media we are modifying the CONTENT to
     * integrate the DTLS-SRTP element(s).
     * @param localContent The element containing the media CONTENT and its
     * TRANSPORT.
     * @param remoteContent The element containing the media CONTENT and its
     * TRANSPORT for the remote peer. Null, if the local peer is the initiator
     * of the call.
     * @return <tt>true</tt> if any DTLS-SRTP element has been added to the
     * specified <tt>localContent</tt>; <tt>false</tt>, otherwise.
     */
    private boolean setDtlsEncryptionOnContent(
            MediaType mediaType,
            ContentPacketExtension localContent,
            ContentPacketExtension remoteContent)
    {
        CallPeerJabberImpl peer = getPeer();
        boolean b = false;

        ProtocolProviderServiceJabberImpl protocolProvider
            = peer.getProtocolProvider();
        AccountID accountID = protocolProvider.getAccountID();
        SrtpControls srtpControls = getSrtpControls();

        if (accountID.getAccountPropertyBoolean(
                    ProtocolProviderFactory.DEFAULT_ENCRYPTION,
                    true)
                && accountID.isEncryptionProtocolEnabled(
                        SrtpControlType.DTLS_SRTP))
        {
            boolean addFingerprintToLocalTransport;

            if (remoteContent == null) // initiator
            {
                addFingerprintToLocalTransport
                    = protocolProvider.isFeatureSupported(
                            peer.getAddressAsJid(),
                            ProtocolProviderServiceJabberImpl
                                .URN_XMPP_JINGLE_DTLS_SRTP);
            }
            else // responder
            {
                addFingerprintToLocalTransport
                    = addDtlsAdvertisedEncryptions(
                            false,
                            remoteContent,
                            mediaType,
                            false);
            }
            if (addFingerprintToLocalTransport)
            {
                DtlsControl dtlsControl
                    = (DtlsControl)
                        srtpControls.getOrCreate(
                                mediaType,
                                SrtpControlType.DTLS_SRTP);

                if (dtlsControl != null)
                {
                    DtlsControl.Setup setup
                        = (remoteContent == null)
                            ? DtlsControl.Setup.PASSIVE
                            : DtlsControl.Setup.ACTIVE;

                    dtlsControl.setSetup(setup);
                    b = true;

                    setDtlsEncryptionOnTransport(
                            mediaType,
                            localContent,
                            remoteContent);
                }
            }
        }
        /*
         * If we haven't advertised DTLS-SRTP in our (media) description, then
         * DTLS-SRTP shouldn't be functioning as far as we're concerned.
         */
        if (!b)
        {
            SrtpControl dtlsControl
                = srtpControls.get(mediaType, SrtpControlType.DTLS_SRTP);

            if (dtlsControl != null)
            {
                srtpControls.remove(mediaType, SrtpControlType.DTLS_SRTP);
                dtlsControl.cleanup(null);
            }
        }
        return b;
    }

    /**
     * Sets DTLS-SRTP element(s) to the TRANSPORT element of the CONTENT for a
     * given media.
     *
     * @param mediaType The type of media we are modifying the CONTENT to
     * integrate the DTLS-SRTP element(s).
     * @param localContent The element containing the media CONTENT and its
     * TRANSPORT.
     */
    private boolean setDtlsEncryptionOnTransport(
            MediaType mediaType,
            ContentPacketExtension localContent,
            ContentPacketExtension remoteContent)
    {
        IceUdpTransportPacketExtension localTransport
            = localContent.getFirstChildOfType(
                    IceUdpTransportPacketExtension.class);
        boolean b = false;

        if (localTransport == null)
            return b;

        SrtpControls srtpControls = getSrtpControls();
        DtlsControl dtlsControl = (DtlsControl) srtpControls.get(mediaType, SrtpControlType.DTLS_SRTP);

        if (dtlsControl != null)
        {
            CallJabberImpl.setDtlsEncryptionOnTransport(dtlsControl, localTransport);
            b = true;
        }
        return b;
    }

    /**
     * Sets DTLS-SRTP element(s) to the TRANSPORT element of a specified list of
     * CONTENT elements.
     *
     * @param localContents The elements containing the media CONTENT elements
     * and their respective TRANSPORT elements.
     */
    private void setDtlsEncryptionOnTransports(
            List<ContentPacketExtension> remoteContents,
            List<ContentPacketExtension> localContents)
    {
        for (ContentPacketExtension localContent : localContents)
        {
            RtpDescriptionPacketExtension description
                = JingleUtils.getRtpDescription(localContent);

            if (description != null)
            {
                MediaType mediaType = JingleUtils.getMediaType(localContent);

                if (mediaType != null)
                {
                    ContentPacketExtension remoteContent
                        = (remoteContents == null)
                            ? null
                            : TransportManagerJabberImpl.findContentByName(
                                    remoteContents,
                                    localContent.getName());

                    setDtlsEncryptionOnTransport(
                            mediaType,
                            localContent,
                            remoteContent);
                }
            }
        }
    }

    /**
     * Sets the jingle transports that this
     * <tt>CallPeerMediaHandlerJabberImpl</tt> supports. Unknown transports are
     * ignored, and the <tt>transports</tt> <tt>Collection</tt> is put into
     * order depending on local preference.
     *
     * Currently only ice and raw-udp are recognized, with ice being preffered
     * over raw-udp
     *
     * @param transports A <tt>Collection</tt> of XML namespaces of jingle
     * transport elements to be set as the supported jingle transports for this
     * <tt>CallPeerMediaHandlerJabberImpl</tt>
     */
    public void setSupportedTransports(Collection<String> transports)
    {
        if (transports == null)
            return;

        String ice
                = ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_ICE_UDP_1;
        String rawUdp
                = ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_RAW_UDP_0;

        int size = 0;
        for(String transport : transports)
            if (ice.equals(transport) || rawUdp.equals(transport))
                size++;

        if (size > 0)
        {
            synchronized (supportedTransportsSyncRoot)
            {
                supportedTransports = new String[size];
                int i = 0;

                // we prefer ice over raw-udp
                if (transports.contains(ice))
                {
                    supportedTransports[i] = ice;
                    i++;
                }

                if (transports.contains(rawUdp))
                {
                    supportedTransports[i] = rawUdp;
                    i++;
                }
            }
        }
    }

    /**
     * Gets the <tt>inputevt</tt> support: true for enable, false for disable.
     *
     * @return The state of inputevt support: true for enable, false for
     * disable.
     */
    public boolean getLocalInputEvtAware()
    {
        return this.localInputEvtAware;
    }

    /**
     * Enable or disable <tt>inputevt</tt> support (remote-control).
     *
     * @param enable new state of inputevt support
     */
    public void setLocalInputEvtAware(boolean enable)
    {
        localInputEvtAware = enable;
    }

    /**
     * Detects and adds ZRTP available encryption method present in the
     * description given in parameter.
     *
     * @param isInitiator True if the local call instance is the initiator of
     * the call. False otherwise.
     * @param description The DESCRIPTION element of the JINGLE element which
     * contains the PAYLOAD-TYPE and (more important here) the ENCRYPTION.
     * @param mediaType The type of media (AUDIO or VIDEO).
     */
    private void addZrtpAdvertisedEncryptions(
        boolean isInitiator,
        RtpDescriptionPacketExtension description,
        MediaType mediaType)
    {
        CallPeer peer = getPeer();
        Call call = peer.getCall();

        // Conforming to XEP-0167 schema there is 0 or 1 ENCRYPTION element for
        // a given DESCRIPTION.
        EncryptionPacketExtension encryptionPacketExtension
            = description.getFirstChildOfType(
                    EncryptionPacketExtension.class);

        if(encryptionPacketExtension != null)
        {
            AccountID accountID = peer.getProtocolProvider().getAccountID();

            if (accountID.getAccountPropertyBoolean(
                        ProtocolProviderFactory.DEFAULT_ENCRYPTION,
                        true)
                    && accountID.isEncryptionProtocolEnabled(
                            SrtpControlType.ZRTP)
                    && call.isSipZrtpAttribute())
            {
                // ZRTP
                ZrtpHashPacketExtension zrtpHashPacketExtension
                    = encryptionPacketExtension.getFirstChildOfType(
                            ZrtpHashPacketExtension.class);

                if ((zrtpHashPacketExtension != null)
                        && (zrtpHashPacketExtension.getValue() != null))
                {
                    addAdvertisedEncryptionMethod(SrtpControlType.ZRTP);
                }
            }
        }
    }

    /**
     * Detects and adds SDES available encryption method present in the
     * description given in parameter.
     *
     * @param isInitiator True if the local call instance is the initiator of
     * the call. False otherwise.
     * @param description The DESCRIPTION element of the JINGLE element which
     * contains the PAYLOAD-TYPE and (more important here) the ENCRYPTION.
     * @param mediaType The type of media (AUDIO or VIDEO).
     */
    private void addSDesAdvertisedEncryptions(
        boolean isInitiator,
        RtpDescriptionPacketExtension description,
        MediaType mediaType)
    {
        CallPeer peer = getPeer();

        // Conforming to XEP-0167 schema there is 0 or 1 ENCRYPTION element for
        // a given DESCRIPTION.
        EncryptionPacketExtension encryptionPacketExtension
            = description.getFirstChildOfType(
                    EncryptionPacketExtension.class);

        if(encryptionPacketExtension != null)
        {
            AccountID accountID = peer.getProtocolProvider().getAccountID();

            // SDES
            if(accountID.getAccountPropertyBoolean(
                        ProtocolProviderFactory.DEFAULT_ENCRYPTION,
                        true)
                    && accountID.isEncryptionProtocolEnabled(
                            SrtpControlType.SDES))
            {
                SrtpControls srtpControls = getSrtpControls();
                SDesControl sdesControl
                    = (SDesControl)
                        srtpControls.getOrCreate(
                                mediaType,
                                SrtpControlType.SDES);
                SrtpCryptoAttribute selectedSdes
                    = selectSdesCryptoSuite(
                            isInitiator,
                            sdesControl,
                            encryptionPacketExtension);

                if(selectedSdes != null)
                {
                    //found an SDES answer, remove all other controls
                    removeAndCleanupOtherSrtpControls(
                            mediaType,
                            SrtpControlType.SDES);
                    addAdvertisedEncryptionMethod(SrtpControlType.SDES);
                }
                else
                {
                    sdesControl.cleanup(null);
                    srtpControls.remove(mediaType, SrtpControlType.SDES);
                }
            }
        }
        // If we were initiating the encryption, and the remote peer does not
        // manage it, then we must remove the unusable SDES srtpControl.
        else if(isInitiator)
        {
            // SDES
            SrtpControl sdesControl
                = getSrtpControls().remove(mediaType, SrtpControlType.SDES);

            if (sdesControl != null)
                sdesControl.cleanup(null);
        }
    }

    /**
     * Returns the selected SDES crypto suite selected.
     *
     * @param isInitiator True if the local call instance is the initiator of
     * the call. False otherwise.
     * @param sDesControl The SDES based SRTP MediaStream encryption
     * control.
     * @param encryptionPacketExtension The ENCRYPTION element received from the
     * remote peer. This may contain the SDES crypto suites available for the
     * remote peer.
     *
     * @return The selected SDES crypto suite supported by both the local and
     * the remote peer. Or null, if there is no crypto suite supported by both
     * of the peers.
     */
    private SrtpCryptoAttribute selectSdesCryptoSuite(
        boolean isInitiator,
        SDesControl sDesControl,
        EncryptionPacketExtension encryptionPacketExtension)
    {
        List<CryptoPacketExtension> cryptoPacketExtensions
            = encryptionPacketExtension.getCryptoList();
        List<SrtpCryptoAttribute> peerAttributes
            = new ArrayList<>(cryptoPacketExtensions.size());

        for (CryptoPacketExtension cpe : cryptoPacketExtensions)
            peerAttributes.add(
                SrtpCryptoAttribute.create(
                    cpe.getTag(),
                    cpe.getCryptoSuite(),
                    cpe.getKeyParams(),
                    cpe.getSessionParams()));

        return
            isInitiator
                ? sDesControl.initiatorSelectAttribute(peerAttributes)
                : sDesControl.responderSelectAttribute(peerAttributes);
    }

    /**
     * Returns if the remote peer supports ZRTP.
     *
     * @param encryptionPacketExtension The ENCRYPTION element received from
     * the remote peer. This may contain the ZRTP packet element for the remote
     * peer.
     *
     * @return True if the remote peer supports ZRTP. False, otherwise.
     */
    private boolean isRemoteZrtpCapable(
        EncryptionPacketExtension encryptionPacketExtension)
    {
        return
            (encryptionPacketExtension.getFirstChildOfType(
                    ZrtpHashPacketExtension.class)
                != null);
    }

    /**
     * Sets ZRTP element to the ENCRYPTION element of the DESCRIPTION for a
     * given media.
     *
     * @param mediaType The type of media we are modifying the DESCRIPTION to
     * integrate the ENCRYPTION element.
     * @param description The element containing the media DESCRIPTION and its
     * encryption.
     * @param remoteDescription The element containing the media DESCRIPTION and
     * its encryption for the remote peer. Null, if the local peer is the
     * initiator of the call.
     *
     * @return True if the ZRTP element has been added to encryption. False,
     * otherwise.
     */
    private boolean setZrtpEncryptionOnDescription(
        MediaType mediaType,
        RtpDescriptionPacketExtension description,
        RtpDescriptionPacketExtension remoteDescription)
    {
        CallPeer peer = getPeer();
        Call call = peer.getCall();

        boolean isRemoteZrtpCapable;

        if (remoteDescription == null)
            isRemoteZrtpCapable = true;
        else
        {
            // Conforming to XEP-0167 schema there is 0 or 1 ENCRYPTION element
            // for a given DESCRIPTION.
            EncryptionPacketExtension remoteEncryption
                = remoteDescription.getFirstChildOfType(
                        EncryptionPacketExtension.class);

            isRemoteZrtpCapable
                = (remoteEncryption != null)
                    && isRemoteZrtpCapable(remoteEncryption);
        }

        boolean zrtpHashSet = false; // Will become true if at least one is set.
        if (isRemoteZrtpCapable)
        {
            AccountID accountID = peer.getProtocolProvider().getAccountID();

            if(accountID.getAccountPropertyBoolean(
                        ProtocolProviderFactory.DEFAULT_ENCRYPTION,
                        true)
                    && accountID.isEncryptionProtocolEnabled(
                            SrtpControlType.ZRTP)
                    && call.isSipZrtpAttribute())
            {
                ZrtpControl zrtpControl
                    = (ZrtpControl)
                        getSrtpControls().getOrCreate(
                                mediaType,
                                SrtpControlType.ZRTP);
                int numberSupportedVersions
                    = zrtpControl.getNumberSupportedVersions();

                for (int i = 0; i < numberSupportedVersions; i++)
                {
                    String helloHash[] = zrtpControl.getHelloHashSep(i);

                    if ((helloHash != null) && (helloHash[1].length() > 0))
                    {
                        ZrtpHashPacketExtension hash
                            = new ZrtpHashPacketExtension();

                        hash.setVersion(helloHash[0]);
                        hash.setValue(helloHash[1]);

                        EncryptionPacketExtension encryption
                            = description.getFirstChildOfType(
                                    EncryptionPacketExtension.class);

                        if (encryption == null)
                        {
                            encryption = new EncryptionPacketExtension();
                            description.addChildExtension(encryption);
                        }
                        encryption.addChildExtension(hash);
                        zrtpHashSet = true;
                    }
                }
            }
        }

        return zrtpHashSet;
    }

    /**
     * Sets SDES element(s) to the ENCRYPTION element of the DESCRIPTION for a
     * given media.
     *
     * @param mediaType The type of media we are modifying the DESCRIPTION to
     * integrate the ENCRYPTION element.
     * @param localDescription The element containing the media DESCRIPTION and
     * its encryption.
     * @param remoteDescription The element containing the media DESCRIPTION and
     * its encryption for the remote peer. Null, if the local peer is the
     * initiator of the call.
     *
     * @return True if the crypto element has been added to encryption. False,
     * otherwise.
     */
    private boolean setSDesEncryptionOnDescription(
        MediaType mediaType,
        RtpDescriptionPacketExtension localDescription,
        RtpDescriptionPacketExtension remoteDescription)
    {
        CallPeer peer = getPeer();

        AccountID accountID = peer.getProtocolProvider().getAccountID();

        // check if SDES and encryption is enabled at all
        if (accountID.getAccountPropertyBoolean(
                    ProtocolProviderFactory.DEFAULT_ENCRYPTION,
                    true)
                && accountID.isEncryptionProtocolEnabled(
                        SrtpControlType.SDES))
        {
            // get or create the control
            SrtpControls srtpControls = getSrtpControls();
            SDesControl sdesControl
                = (SDesControl)
                    srtpControls.getOrCreate(mediaType, SrtpControlType.SDES);
            // set the enabled ciphers suites
            String ciphers
                = accountID.getAccountPropertyString(
                        ProtocolProviderFactory.SDES_CIPHER_SUITES);

            if (ciphers == null)
            {
                ciphers =
                    JabberActivator.getResources().getSettingsString(
                        SDesControl.SDES_CIPHER_SUITES);
            }
            sdesControl.setEnabledCiphers(Arrays.asList(ciphers.split(",")));

            // act as initiator
            if (remoteDescription == null)
            {
                EncryptionPacketExtension localEncryption
                    = localDescription.getFirstChildOfType(
                            EncryptionPacketExtension.class);

                if(localEncryption == null)
                {
                    localEncryption = new EncryptionPacketExtension();
                    localDescription.addChildExtension(localEncryption);
                }
                for(SrtpCryptoAttribute ca:
                        sdesControl.getInitiatorCryptoAttributes())
                {
                    CryptoPacketExtension crypto = new CryptoPacketExtension(
                        ca.getTag(),
                        ca.getCryptoSuite().encode(),
                        ca.getKeyParamsString(),
                        ca.getSessionParamsString());
                    localEncryption.addChildExtension(crypto);
                }

                return true;
            }
            // act as responder
            else
            {
                // Conforming to XEP-0167 schema there is 0 or 1 ENCRYPTION
                // element for a given DESCRIPTION.
                EncryptionPacketExtension remoteEncryption
                    = remoteDescription.getFirstChildOfType(
                            EncryptionPacketExtension.class);

                if(remoteEncryption != null)
                {
                    SrtpCryptoAttribute selectedSdes = selectSdesCryptoSuite(
                            false,
                            sdesControl,
                            remoteEncryption);

                    if(selectedSdes != null)
                    {
                        EncryptionPacketExtension localEncryption
                            = localDescription.getFirstChildOfType(
                                    EncryptionPacketExtension.class);

                        if(localEncryption == null)
                        {
                            localEncryption = new EncryptionPacketExtension();
                            localDescription.addChildExtension(localEncryption);
                        }

                        CryptoPacketExtension crypto
                            = new CryptoPacketExtension(
                                selectedSdes.getTag(),
                                selectedSdes.getCryptoSuite().encode(),
                                selectedSdes.getKeyParamsString(),
                                selectedSdes.getSessionParamsString());

                        localEncryption.addChildExtension(crypto);

                        return true;
                    }
                    else
                    {
                        // none of the offered suites match, destroy the sdes
                        // control
                        sdesControl.cleanup(null);
                        srtpControls.remove(mediaType, SrtpControlType.SDES);
                        logger.warn(
                                "Received unsupported sdes crypto attribute");
                    }
                }
                else
                {
                    // peer doesn't offer any SDES attribute, destroy the sdes
                    // control
                    sdesControl.cleanup(null);
                    srtpControls.remove(mediaType, SrtpControlType.SDES);
                }
            }
        }

        return false;
    }

    /**
     * Selects a specific encryption protocol if it is the preferred (only used
     * by the callee).
     *
     * @param mediaType The type of media (AUDIO or VIDEO).
     * @param localDescription The element containing the media DESCRIPTION and
     * its encryption.
     * @param remoteDescription The element containing the media DESCRIPTION and
     * its encryption for the remote peer; <tt>null</tt> if the local peer is
     * the initiator of the call.
     * @return <tt>true</tt> if the specified encryption protocol has been
     * selected; <tt>false</tt>, otherwise
     */
    private boolean setAndAddPreferredEncryptionProtocol(
        SrtpControlType srtpControlType,
        MediaType mediaType,
        RtpDescriptionPacketExtension localDescription,
        RtpDescriptionPacketExtension remoteDescription)
    {
        // SDES
        if(srtpControlType == SrtpControlType.SDES)
        {
            addSDesAdvertisedEncryptions(
                    false,
                    remoteDescription,
                    mediaType);
            if(setSDesEncryptionOnDescription(
                    mediaType,
                    localDescription,
                    remoteDescription))
            {
                // Stop once an encryption advertisement has been chosen.
                return true;
            }
        }
        // ZRTP
        else if(srtpControlType == SrtpControlType.ZRTP)
        {
            if(setZrtpEncryptionOnDescription(
                    mediaType,
                    localDescription,
                    remoteDescription))
            {
                addZrtpAdvertisedEncryptions(
                        false,
                        remoteDescription,
                        mediaType);
                // Stop once an encryption advertisement has been chosen.
                return true;
            }
        }
        return false;
    }
}
