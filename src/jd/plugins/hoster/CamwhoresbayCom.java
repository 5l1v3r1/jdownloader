//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
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

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.plugins.components.antiDDoSForHost;

import jd.PluginWrapper;
import jd.controlling.downloadcontroller.SingleDownloadController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.SiteType.SiteTemplate;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "camwhoresbay.com" }, urls = { "https?://(?:www\\.)?camwhoresbay\\.com/(embed/\\d+|videos/\\d+/[a-z0-9\\-]+/)" })
public class CamwhoresbayCom extends antiDDoSForHost {
    public CamwhoresbayCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.camwhoresbay.com/");
    }

    /* DEV NOTES */
    // Tags: porn host
    // protocol: no https
    // other:
    /* Extension which will be used if no correct extension is found */
    private static final String default_Extension         = ".mp4";
    /* Connection stuff */
    private final boolean       FREE_RESUME               = true;
    private final int           FREE_MAXCHUNKS            = 0;
    private final int           FREE_MAXDOWNLOADS         = -1;
    private final boolean       ACCOUNT_FREE_RESUME       = true;
    private final int           ACCOUNT_FREE_MAXCHUNKS    = 0;
    private final int           ACCOUNT_FREE_MAXDOWNLOADS = -1;
    // private final boolean ACCOUNT_PREMIUM_RESUME = true;
    // private final int ACCOUNT_PREMIUM_MAXCHUNKS = 0;
    private String              dllink                    = null;
    private boolean             server_issues             = false;
    private boolean             is_private_video          = false;

    @Override
    public String getAGBLink() {
        return "https://www.camwhoresbay.com/terms/";
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
        return new Regex(link.getPluginPatternMatcher(), "/(?:videos|embed)/(\\d+)").getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        dllink = null;
        server_issues = false;
        String filename = jd.plugins.hoster.KernelVideoSharingCom.regexURLFilename(link.getPluginPatternMatcher());
        if (filename == null) {
            /* For embeddded videos */
            filename = this.getFID(link);
        }
        final String ext = default_Extension;
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        link.setFinalFileName(filename);
        br.setFollowRedirects(true);
        br.setCookie(this.getHost(), "kt_tcookie", "1");
        br.setCookie(this.getHost(), "kt_is_visited", "1");
        getPage(link.getPluginPatternMatcher());
        if (isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        is_private_video = this.br.containsHTML("This video is a private");
        if (is_private_video && br.containsHTML("login-required")) {
            return AvailableStatus.TRUE;
        }
        getDllink(link);
        if (dllink != null && !(Thread.currentThread() instanceof SingleDownloadController)) {
            final Browser br2 = br.cloneBrowser();
            br.setFollowRedirects(true);
            URLConnectionAdapter con = null;
            try {
                con = openAntiDDoSRequestConnection(br2, br2.createHeadRequest(dllink));
                if (con.isOK() && !con.getContentType().contains("html")) {
                    link.setDownloadSize(con.getCompleteContentLength());
                    link.setProperty("directlink", dllink);
                } else {
                    server_issues = true;
                }
            } finally {
                try {
                    if (con != null) {
                        con.disconnect();
                    }
                } catch (final Throwable e) {
                }
            }
        } else {
            /* We cannot be sure whether we have the correct extension or not! */
            link.setName(filename);
        }
        return AvailableStatus.TRUE;
    }

    private void getDllink(final DownloadLink link) throws Exception {
        try {
            dllink = jd.plugins.hoster.KernelVideoSharingCom.getDllink(br, this);
        } catch (final PluginException e) {
            logger.log(e);
            if (!this.br.containsHTML("This video is a private")) {
                throw e;
            }
        }
        if (dllink != null && dllink.contains("login-required")) {
            dllink = null;
        }
    }

    @Override
    public void handleFree(final DownloadLink LINK) throws Exception, PluginException {
        requestFileInformation(LINK);
        doFree(null, LINK, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final Account account, final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (is_private_video && account == null) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            if (is_private_video) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            try {
                br.followConnection();
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    /** TODO: Consider integrating this into KernelVideoSharingCom --> Update it so we can do an "extends KernelVideoSharingCom". */
    private void login(final Account account, final boolean force, final boolean test) throws Exception {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null && !force) {
                    this.br.setCookies(this.getHost(), cookies);
                    if (!test) {
                    }
                    getPage("https://www." + this.getHost() + "/");
                    if (isLoggedIN()) {
                        logger.info("Cookie login successful");
                        account.saveCookies(this.br.getCookies(this.getHost()), "");
                        return;
                    } else {
                        logger.info("Cookie login failed");
                    }
                }
                br.clearCookies(this.getHost());
                getPage("https://www." + this.getHost() + "/login/");
                /*
                 * 2017-01-21: This request will usually return a json with some information about the account. Until now there are no
                 * premium accounts available at all.
                 */
                postPage("/login/", "remember_me=1&action=login&email_link=http%3A%2F%2Fwww." + this.getHost() + "%2Femail%2F&format=json&mode=async&username=" + Encoding.urlEncode(account.getUser()) + "&pass=" + Encoding.urlEncode(account.getPass()));
                if (!isLoggedIN()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, false, true);
        /* Registered users can watch private videos when they follow/subscribe to the uploaders. */
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        account.setConcurrentUsePossible(false);
        ai.setStatus("Registered (free) user");
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        login(account, false, false);
        requestFileInformation(link);
        getDllink(link);
        doFree(account, link, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS, "account_free_directlink");
    }

    private boolean isLoggedIN() {
        return br.getCookie(br.getHost(), "kt_member", Cookies.NOTDELETEDPATTERN) != null;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_FREE_MAXDOWNLOADS;
    }

    private String getTitle(final Browser br, final String url_source) {
        // String title = br.getRegex("<title>(?:Watch Free )?([^<>\"]*?)( / Embed Player| Webcam Porn Video \\-
        // CamWhores\\.TV)?</title>").getMatch(0);
        // if (title == null) {
        // /* Fallback to URL-title */
        // title = new Regex(url, "/videos/\\d+/(.+)").getMatch(0);
        // }
        String filename = br.getRegex("property=\"og:title\" content=\"([^<>\"]+)\"").getMatch(0);
        /* 2018-12-04: Website does not contain better titles than URL --> Always use title we find in our URLs */
        final String urlregex = "/([^/]+)/$";
        String filename_url = new Regex(url_source, urlregex).getMatch(0);
        if (StringUtils.isEmpty(filename_url)) {
            filename_url = new Regex(br.getURL(), urlregex).getMatch(0);
        }
        if (StringUtils.isEmpty(filename)) {
            /* Fallback */
            filename = filename_url;
        }
        if (filename == null) {
            /* Final fallback */
            filename = getFID(this.getDownloadLink());
        }
        return filename;
    }

    public static boolean isOffline(final Browser br) {
        if (br.getHttpConnection().getResponseCode() == 404) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.KernelVideoSharing;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
