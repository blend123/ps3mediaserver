/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.StringTokenizer;

import javax.swing.JComponent;
import javax.swing.JTextField;

import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.dlna.DLNAResource;
import net.pms.formats.Format;
import net.pms.io.OutputParams;
import net.pms.io.PipeIPCProcess;
import net.pms.io.ProcessWrapper;
import net.pms.io.ProcessWrapperImpl;
import net.pms.Messages;
import net.pms.network.HTTPResource;
import net.pms.PMS;

import static org.apache.commons.lang.BooleanUtils.isTrue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

/**
 * Pure FFmpeg video player.
 */
public class FFMpegVideo extends Player {
	private static final Logger LOGGER = LoggerFactory.getLogger(FFMpegVideo.class);
	private static final Map<String, Boolean> IGNORE_CUSTOM_OPTION = new HashMap<String, Boolean>();
	private JTextField ffmpeg;

	// FIXME we have an id() accessor for this; no need for the field to be public
	@Deprecated
	public static final String ID = "ffmpegvideo";

	// XXX this is a hack; see getSanitizedCustomArgs()
	static {
		IGNORE_CUSTOM_OPTION.put("-acodec",  true);
		IGNORE_CUSTOM_OPTION.put("-f",       true);
		IGNORE_CUSTOM_OPTION.put("-target",  true);
		IGNORE_CUSTOM_OPTION.put("-threads", true);
		IGNORE_CUSTOM_OPTION.put("-vcodec",  true);
	}

	/**
	 * Returns a string representing the rescale spec for this transcode i.e. the ffmpeg -vf value
	 * used to resize a video that's too wide and/or high for the specified renderer. If the renderer
	 * has no size limits, or there's no media metadata, or the video is within the renderer's size limits
	 * <code>null</code> is returned.
	 *
	 * @param renderer the DLNA renderer the video is being streamed to
	 * @param media metadata for the DLNA resource which is being transcoded
	 * @return a rescale spec <code>String</code> or <code>null</code> if resizing isn't required.
	 */
	public static String getRescaleSpec(RendererConfiguration renderer, DLNAMediaInfo media) {
		String rescaleSpec = null;
		boolean isResolutionTooHighForRenderer = renderer.isVideoRescale() // renderer defines a max width/height
			&& (media != null)
			&& (
					(media.getWidth() > renderer.getMaxVideoWidth())
					||
					(media.getHeight() > renderer.getMaxVideoHeight())
			   );

		if (isResolutionTooHighForRenderer) {
			rescaleSpec = String.format(
				// http://stackoverflow.com/a/8351875
				"scale=iw*min(%1$d/iw\\,%2$d/ih):ih*min(%1$d/iw\\,%2$d/ih),pad=%1$d:%2$d:(%1$d-iw)/2:(%2$d-ih)/2",
				renderer.getMaxVideoWidth(),
				renderer.getMaxVideoHeight()
			);
		}

		return rescaleSpec;
	}

	/**
	 * Takes a {@link RendererConfiguration} and returns a {@link List} of <code>String</code>s representing ffmpeg output options
	 * (i.e. options that define the output file's video codec, audio codec and container)
	 * compatible with the renderer's <code>TranscodeVideo</code> profile. Implemented here as a static method
	 * so that it can be used by {@link FFmpegWebVideo} and any other engines that wrap ffmpeg.
	 *
	 * @param renderer The {@link RendererConfiguration} instance whose <code>TranscodeVideo</code> profile is to be processed.
	 * @return a {@link List} of <code>String</code>s representing the ffmpeg output parameters for the renderer according
	 * to its <code>TranscodeVideo</code> profile.
	 */
	public static List<String> getTranscodeVideoOptions(RendererConfiguration renderer) {
		List<String> transcodeOptions = new ArrayList<String>();

		if (renderer.isTranscodeToWMV()) { // WMV
			transcodeOptions.add("-vcodec");
			transcodeOptions.add("wmv2");

			transcodeOptions.add("-acodec");
			transcodeOptions.add("wma2");

			transcodeOptions.add("-f");
			transcodeOptions.add("asf");
		} else { // MPEGPSAC3 or MPEGTSAC3
			transcodeOptions.add("-vcodec");
			transcodeOptions.add("mpeg2video");

			transcodeOptions.add("-acodec");
			transcodeOptions.add("ac3");

			if (renderer.isTranscodeToMPEGTSAC3()) { // MPEGTSAC3
				transcodeOptions.add("-f");
				transcodeOptions.add("mpegts");
			} else { // default: MPEGPSAC3
				transcodeOptions.add("-f");
				transcodeOptions.add("vob");
			}
		}

		return transcodeOptions;
	}

	@Override
	public int purpose() {
		return VIDEO_SIMPLEFILE_PLAYER;
	}

	@Override
	// TODO make this static so it can replace ID, instead of having both
	public String id() {
		return ID;
	}

	@Override
	public boolean isTimeSeekable() {
		return true;
	}

	@Override
	@Deprecated
	public boolean avisynth() {
		return false;
	}

	@Override
	public String name() {
		return "FFmpeg";
	}

	@Override
	public int type() {
		return Format.VIDEO;
	}

	// unused; return this array for backwards-compatibility
	@Deprecated
	protected String[] getDefaultArgs() {
		return new String[] { "-vcodec", "mpeg2video", "-f", "vob", "-acodec", "ac3" };
	}

	@Override
	@Deprecated
	public String[] args() {
		return getDefaultArgs(); // unused; return this array for for backwards compatibility
	}

	// remove invalid (i.e. output) options from
	// Transcoder Settings -> FFmpeg -> Custom settings
	// and return them as a list of strings.
	// this is called each time launchTranscode is called, which allows
	// the options to be tweaked without restarting PMS (the same
	// as custom MEncoder options)
	// XXX this is a hack; we can't trap all "bad" options.
	// either 1) don't allow custom options to be set 2) whitelist options (e.g. -vf)
	// or 3) allow any options and let ffmpeg decide whether the added options are valid
	private List<String> getSanitizedCustomArgs() {
		List<String> customOptionsList = new ArrayList<String>();
		String customOptionsString = PMS.getConfiguration().getFfmpegSettings();
		int tokens = 0;

		if (customOptionsString != null) {
			LOGGER.info("Custom ffmpeg options: {}", customOptionsString);
			StringTokenizer st = new StringTokenizer(customOptionsString, " ");
			tokens = st.countTokens();
			boolean skip = false;

			while (st.hasMoreTokens()) {
				String token = st.nextToken();

				if (skip) { // don't append to customOptionsList
					skip = false;
				} else {
					Boolean value = IGNORE_CUSTOM_OPTION.get(token);

					if (value == null) {
						customOptionsList.add(token); // add this token
					} else if (isTrue(value)) { // true: skip this option and its corresponding value e.g. -foo bar
						skip = true;
					} // false: skip this value-less option e.g. -foo
				}
			}
		}

		if (tokens > customOptionsList.size()) {
			LOGGER.warn(
				"The following ffmpeg options cannot be changed and one or more have been ignored: {}",
				IGNORE_CUSTOM_OPTION.keySet()
			);
		}

		return customOptionsList;
	}

	// XXX hardwired to false and not referenced anywhere else in the codebase
	@Deprecated
	public boolean mplayer() {
		return false;
	}

	@Override
	public String mimeType() {
		return HTTPResource.VIDEO_TRANSCODE;
	}

	@Override
	public String executable() {
		return PMS.getConfiguration().getFfmpegPath();
	}

	@Override
	public ProcessWrapper launchTranscode(
		String fileName,
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params
	) throws IOException {
		return getFFMpegTranscode(fileName, dlna, media, params, null);
	}

	// XXX pointless redirection of launchTranscode
	// TODO remove this method and move its body into launchTranscode
	// TODO call setAudioAndSubs to populate params with audio track/subtitles metadata
	@Deprecated
	protected ProcessWrapperImpl getFFMpegTranscode(
		String fileName,
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params,
		String args[]
	) throws IOException {
		int nThreads = PMS.getConfiguration().getNumberOfCpuCores();
		List<String> cmdList = new ArrayList<String>();
		RendererConfiguration renderer = params.mediaRenderer;

		cmdList.add(executable());

		cmdList.add("-loglevel");
		cmdList.add("warning");

		if (params.timeseek > 0) {
			cmdList.add("-ss");
			cmdList.add("" + params.timeseek);
		}

		// decoder threads
		cmdList.add("-threads");
		cmdList.add("" + nThreads);

		cmdList.add("-i");
		cmdList.add(fileName);

		// encoder threads
		cmdList.add("-threads");
		cmdList.add("" + nThreads);

		if (params.timeend > 0) {
			cmdList.add("-t");
			cmdList.add("" + params.timeend);
		}

		// quality (bitrate)
		String sMaxVideoBitrate = renderer.getMaxVideoBitrate(); // currently Mbit/s
		int iMaxVideoBitrate = 0;

		if (sMaxVideoBitrate != null) {
			try {
				iMaxVideoBitrate = Integer.parseInt(sMaxVideoBitrate);
			} catch (NumberFormatException nfe) {
				LOGGER.error("Can't parse max video bitrate", nfe); // XXX this should be handled in RendererConfiguration
			}
		}

		if (iMaxVideoBitrate != 0) {
			// limit the bitrate
			// FIXME untested
			cmdList.add("-b:v");
			// convert megabits-per-second (as per the current option name: MaxVideoBitrateMbps) to bps
			// FIXME rather than dealing with megabit vs mebibit issues here, this should be left up to the client i.e.
			// the renderer.conf unit should be bits-per-second (and the option should be renamed: MaxVideoBitrateMbps -> MaxVideoBitrate)
			cmdList.add("" + iMaxVideoBitrate * 1000 * 1000);
		} else {
			// preserve the bitrate
			cmdList.add("-sameq");
		}

		// if the source is too large for the renderer, resize it
		String rescale = getRescaleSpec(renderer, media);
		if (rescale != null) {
			cmdList.add("-vf");
			cmdList.add(rescale);
		}

		cmdList.addAll(getSanitizedCustomArgs());
		cmdList.addAll(getTranscodeVideoOptions(renderer));
		cmdList.add("pipe:");

		String[] cmdArray = new String[ cmdList.size() ];
		cmdList.toArray(cmdArray);

		cmdArray = finalizeTranscoderArgs(
			fileName,
			dlna,
			media,
			params,
			cmdArray
		);

		ProcessWrapperImpl pw = new ProcessWrapperImpl(cmdArray, params);
		pw.runInNewThread();

		return pw;
	}

	@Override
	public JComponent config() {
		return config("FFMpegVideo.1");
	}

	protected JComponent config(String languageLabel) {
		FormLayout layout = new FormLayout(
			"left:pref, 0:grow",
			"p, 3dlu, p, 3dlu"
		);
		PanelBuilder builder = new PanelBuilder(layout);
		builder.setBorder(Borders.EMPTY_BORDER);
		builder.setOpaque(false);

		CellConstraints cc = new CellConstraints();

		JComponent cmp = builder.addSeparator(Messages.getString(languageLabel), cc.xyw(2, 1, 1));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		ffmpeg = new JTextField(PMS.getConfiguration().getFfmpegSettings());
		ffmpeg.addKeyListener(new KeyListener() {
			@Override
			public void keyPressed(KeyEvent e) {
			}

			@Override
			public void keyTyped(KeyEvent e) {
			}

			@Override
			public void keyReleased(KeyEvent e) {
				PMS.getConfiguration().setFfmpegSettings(ffmpeg.getText());
			}
		});

		builder.add(ffmpeg, cc.xy(2, 3));

		return builder.getPanel();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isCompatible(DLNAResource resource) {
		if (resource == null || resource.getFormat().getType() != Format.VIDEO) {
			return false;
		}

		DLNAMediaSubtitle subtitle = resource.getMediaSubtitle();

		// Check whether the subtitle actually has a language defined,
		// uninitialized DLNAMediaSubtitle objects have a null language.
		if (subtitle != null && subtitle.getLang() != null) {
			// The resource needs a subtitle, but PMS support for FFmpeg subtitles has not yet been implemented.
			return false;
		}

		try {
			String audioTrackName = resource.getMediaAudio().toString();
			String defaultAudioTrackName = resource.getMedia().getAudioTracksList().get(0).toString();

			if (!audioTrackName.equals(defaultAudioTrackName)) {
				// PMS only supports playback of the default audio track for FFmpeg
				return false;
			}
		} catch (NullPointerException e) {
			LOGGER.trace("FFmpeg cannot determine compatibility based on audio track for "
					+ resource.getSystemName());
		} catch (IndexOutOfBoundsException e) {
			LOGGER.trace("FFmpeg cannot determine compatibility based on default audio track for "
					+ resource.getSystemName());
		}

		Format format = resource.getFormat();

		if (format != null) {
			Format.Identifier id = format.getIdentifier();

			if (id.equals(Format.Identifier.MKV) || id.equals(Format.Identifier.MPG)) {
				return true;
			}
		}

		return false;
	}
}
