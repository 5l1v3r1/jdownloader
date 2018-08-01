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

import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "zbigz.com" }, urls = { "http://(www\\.)?zbigz\\.com/file/[a-z0-9]+/\\d+" })
public class ZbigzCom extends PluginForHost {
    public ZbigzCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://zbigz.com/page-premium-overview");
    }

    @Override
    public String getAGBLink() {
        return "http://zbigz.com/page-therms-of-use";
    }

    private String              DLLINK   = null;
    private static final String NOCHUNKS = "NOCHUNKS";

    /**
     * JD2 CODE. DO NOT USE OVERRIDE FOR JD=) COMPATIBILITY REASONS!
     */
    public boolean isProxyRotationEnabledForLinkChecker() {
        return false;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        this.setBrowserExclusive();
        br.setFollowRedirects(false);
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa != null) {
            login(aa, false);
            br.getPage(downloadLink.getDownloadURL());
            if (br.containsHTML("Page not found")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            DLLINK = br.getRedirectLocation();
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            URLConnectionAdapter con = null;
            try {
                con = br.openGetConnection(DLLINK);
                if (!con.getContentType().contains("html")) {
                    downloadLink.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con)).trim());
                    downloadLink.setDownloadSize(con.getLongContentLength());
                } else {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }
            return AvailableStatus.TRUE;
        } else {
            downloadLink.getLinkStatus().setStatusText("Status can only be checked with account enabled");
            return AvailableStatus.UNCHECKABLE;
        }
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        try {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } catch (final Throwable e) {
            if (e instanceof PluginException) {
                throw (PluginException) e;
            }
        }
        throw new PluginException(LinkStatus.ERROR_FATAL, "This file can only be downloaded by registered/premium users");
    }

    private static Object LOCK = new Object();

    @SuppressWarnings({ "deprecation" })
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null && !force) {
                    this.br.setCookies(this.getHost(), cookies);
                    return;
                }
                br.setFollowRedirects(true);
                br.getPage("https://zbigz.com/login");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getHeaders().put("Accept", "application/json, application/xml, text/plain, text/html, *.*");
                br.postPage("https://api.zbigz.com/v1/account/info", "undefined=undefined");
                br.getHeaders().put("Referer", "https://zbigz.com/login");
                /* Important headers!! */
                br.getHeaders().put("Origin", "https://zbigz.com");
                br.getHeaders().put("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundarynA30WitJ3DZ64EB9");
                br.postPageRaw("https://api.zbigz.com/v1/account/auth", "------WebKitFormBoundarynA30WitJ3DZ64EB9\r\nContent-Disposition: form-data; name=\"undefined\"\r\n\r\nundefined\r\n------WebKitFormBoundarynA30WitJ3DZ64EB9--\r\n");
                final String auth_token_name = PluginJSonUtils.getJson(br, "auth_token_name");
                final String auth_token_value = PluginJSonUtils.getJson(br, "auth_token_value");
                if (StringUtils.isEmpty(auth_token_name) || StringUtils.isEmpty(auth_token_value)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.postPageRaw("/v1/account/login", "------WebKitFormBoundaryTKQE2BnA58YToBjt\r\nContent-Disposition: form-data; name=\"login\"\r\n\r\n" + Encoding.urlEncode(account.getUser()) + "\r\n------WebKitFormBoundaryTKQE2BnA58YToBjt\r\nContent-Disposition: form-data; name=\"email\"\r\n\r\n" + Encoding.urlEncode(account.getUser()) + "\r\n------WebKitFormBoundaryTKQE2BnA58YToBjt\r\nContent-Disposition: form-data; name=\"password\"\r\n\r\n" + Encoding.urlEncode(account.getPass()) + "\r\n------WebKitFormBoundaryTKQE2BnA58YToBjt\r\nContent-Disposition: form-data; name=\"csrf_name\"\r\n\r\"" + auth_token_name + "\r\n------WebKitFormBoundaryTKQE2BnA58YToBjt\r\nContent-Disposition: form-data; name=\"csrf_value\"\r\n\r\"" + auth_token_value + "\r\n------WebKitFormBoundaryTKQE2BnA58YToBjt--\r\n");
                if (!br.containsHTML("loginIn \\(true,\\[true,")) {
                    /* Maybe login is valid but it's not a premium account */
                    if (br.containsHTML("loginIn \\(true,")) {
                        logger.info("Free accounts are not supported!");
                        if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nNicht unterstützter Accounttyp!\r\nFalls du denkst diese Meldung sei falsch die Unterstützung dieses Account-Typs sich\r\ndeiner Meinung nach aus irgendeinem Grund lohnt,\r\nkontaktiere uns über das support Forum.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        } else {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUnsupported account type!\r\nIf you think this message is incorrect or it makes sense to add support for this account type\r\ncontact us via our support forum.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        }
                    }
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        try {
            login(account, true);
        } catch (PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setUnlimitedTraffic();
        final Regex info = br.getRegex("loginIn \\(true,\\[true,\\'([^<>\"]*?)\\',\\'([^<>\"]*?)\\'");
        if (info.getMatches().length != 1) {
            account.setValid(false);
            return ai;
        }
        final String expire = info.getMatch(0);
        ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMM yyyy", Locale.ENGLISH));
        final String traffic = info.getMatch(1);
        ai.setTrafficLeft(SizeFormatter.getSize(traffic));
        account.setValid(true);
        ai.setStatus("Premium User");
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        int chunks = -5;
        if (link.getBooleanProperty(ZbigzCom.NOCHUNKS, false)) {
            chunks = 1;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, DLLINK, true, chunks);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!this.dl.startDownload()) {
            try {
                if (dl.externalDownloadStop()) {
                    return;
                }
            } catch (final Throwable e) {
            }
            /* unknown error, we disable multiple chunks */
            if (link.getBooleanProperty(ZbigzCom.NOCHUNKS, false) == false) {
                link.setProperty(ZbigzCom.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
        }
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}