//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;

import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.plugins.components.antiDDoSForHost;

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "myzuka.ru", "myzcloud.me" }, urls = { "https?://(?:www\\.)?myzuka\\.(?:ru|org|fm|me|club)/Song/(\\d+)", "https?://(?:www\\.)?myzcloud\\.me/(?:[a-z]{2}/)?Song/(\\d+)" })
public class MyzcloudMe extends antiDDoSForHost {
    public MyzcloudMe(PluginWrapper wrapper) {
        super(wrapper);
        /* 2020-03-04: Try to avoid IP block: https://board.jdownloader.org/showthread.php?t=80894 */
        this.setStartIntervall(10 * 1000l);
    }

    @Override
    public String getAGBLink() {
        return "http://myzcloud.me/Contacts";
    }

    @SuppressWarnings("deprecation")
    public void correctDownloadLink(final DownloadLink link) {
        /* Forced https */
        if ("myzuka.ru".equals(getHost())) {
            final String newURL = "https://myzuka.me/song/" + new Regex(link.getDownloadURL(), this.getSupportedLinks()).getMatch(0);
            link.setUrlDownload(newURL);
            link.setPluginPatternMatcher(newURL);
        }
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    protected boolean useRUA() {
        /* 2020-02-26: Try to prevent IP bans. */
        return true;
    }

    /** 2020-02-27: This service is blocking all but turkish IPs! Turkish Proxy/VPN required or every request will return 404! */
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(new int[] { 500 });
        getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 500 || br.getHttpConnection().getResponseCode() == 400) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Abused */
        if (br.containsHTML("Трек удален по просьбе правообладателя")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex("<h1>([^<>\"]*?)</h1>").getMatch(0);
        final String filesize = br.getRegex("(\\d{1,2},\\d{1,2}) Мб").getMatch(0);
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setFinalFileName(encodeUnicode(Encoding.htmlDecode(filename.trim())) + ".mp3");
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize + "MB"));
        }
        return AvailableStatus.TRUE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        if (br.getHttpConnection().getResponseCode() == 403) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Server error 403 (limit reached?)", 30 * 60 * 1000l);
        }
        String dllink = br.getRegex("\"(/Song/dl/[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("\"(/Song/Play/[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                logger.info("Could not find downloadurl, trying to get streamurl");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getPage("/Song/GetPlayFileUrl/" + new Regex(downloadLink.getDownloadURL(), this.getSupportedLinks()).getMatch(0));
                if (br.getHttpConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403 - file not downloadable?", 3 * 60 * 60 * 1000l);
                }
                dllink = br.getRegex("\"(https?://[^<>\"]*?)\"").getMatch(0);
                if (dllink != null) {
                    logger.info("Found streamurl");
                    dllink = Encoding.unicodeDecode(dllink);
                } else {
                    logger.warning("Failed to find streamurl");
                }
            }
        }
        if (dllink != null) {
            dllink = Encoding.htmlDecode(dllink);
            br.setFollowRedirects(false);
            br.getPage(dllink);
            dllink = br.getRedirectLocation();
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* 2020-02-27: Not required anymore */
        // dllink = dllink + "?t=" + System.currentTimeMillis();
        br.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, true, 1);
        if (dl.getConnection().getContentType().contains("html") || dl.getConnection().getContentType().contains("gif")) {
            try {
                br.followConnection();
            } catch (final IOException e) {
                logger.log(e);
            }
            if (dl.getConnection().getContentType().contains("gif")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 15 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403 (limit reached?)", 5 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 500) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 500", 30 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setProperty("directlink", dllink);
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }
}