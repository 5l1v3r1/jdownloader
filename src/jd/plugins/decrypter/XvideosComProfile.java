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
package jd.plugins.decrypter;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.plugins.components.config.XvideosComConfig;
import org.jdownloader.plugins.config.PluginJsonConfig;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class XvideosComProfile extends PluginForDecrypt {
    public XvideosComProfile(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "xvideos.com", "xvideos.es", "xvideos2.com", "xvideos2.es", "xvideos3.com", "xvideos3.es" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:profiles|(?:pornstar-|amateur-|model-)?(?:channels|models))/[A-Za-z0-9\\-_]+(?:/photos/\\d+/[A-Za-z0-9\\-_]+)?");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.addAllowedResponseCodes(new int[] { 400 });
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        if (parameter.matches(".+/photos/.+")) {
            crawlPhotos(parameter, decryptedLinks);
        } else {
            crawlVideos(parameter, decryptedLinks);
        }
        if (decryptedLinks.size() == 0) {
            logger.warning("Failed to find and content for: " + parameter);
            return decryptedLinks;
        }
        return decryptedLinks;
    }

    private void crawlVideos(final String parameter, final ArrayList<DownloadLink> decryptedLinks) throws IOException, PluginException {
        final ArrayList<String> dupeList = new ArrayList<String>();
        final Regex urlinfo = new Regex(parameter, "https?://[^/]+/([^/]+)/(.+)");
        final String type = urlinfo.getMatch(0);
        final String username = urlinfo.getMatch(1);
        if (!br.getURL().contains(username)) {
            /* E.g. redirect to mainpage */
            logger.info("Profile does not exist anymore");
            decryptedLinks.add(this.createOfflinelink(parameter));
            return;
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(Encoding.htmlDecode(username.trim()));
        fp.addLinks(decryptedLinks);
        short pageNum = 0;
        int decryptedLinksNum;
        final boolean fast_linkcheck = PluginJsonConfig.get(XvideosComConfig.class).isEnableFastLinkcheckForProfileCrawler();
        do {
            logger.info(String.format("Decrypting page %d", pageNum));
            decryptedLinksNum = 0;
            br.getPage("/" + type + "/" + username + "/videos/best/" + pageNum);
            // users don't always have profile... as guardo finds links from google... false positive.
            if (br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 400) {
                return;
            } else if (br.toString().matches("<h4 class=\"text-center\">[^<]+  hat keine hochgeladene Videos</h4>\\s*")) {
                logger.info("This user does not have any videos");
                decryptedLinks.add(this.createOfflinelink(parameter));
                return;
            }
            final String[] links = br.getRegex("(/prof-video-click/[^/]+/[^/]+/\\d+((?:/THUMBNUM)?/[^/\"\\']+)?)").getColumn(0);
            if (!br.containsHTML("profile-listing-uploads") && !br.containsHTML("profile-videos-sort") && (links == null || links.length == 0)) {
                logger.info("All videos found or this user does not have any videos");
                break;
            }
            if (links == null || links.length == 0) {
                break;
            }
            decryptedLinksNum = links.length;
            for (String singleLink : links) {
                if (this.isAbort()) {
                    return;
                }
                final String linkid = new Regex(singleLink, "prof-video-click/[^/]+/[^/]+/(\\d+)").getMatch(0);
                /* Only add new URLs */
                if (!dupeList.contains(linkid)) {
                    singleLink = "https://www." + this.getHost() + singleLink;
                    final String url_name = new Regex(singleLink, "/\\d+/(?:THUMBNUM/)?(.+)").getMatch(0);
                    final String name_temp;
                    final DownloadLink dl = createDownloadlink(singleLink);
                    if (fast_linkcheck) {
                        /* Usually we will crawl a lot of URLs at this stage --> Set onlinestatus right away! */
                        dl.setAvailable(true);
                    }
                    fp.add(dl);
                    dl.setLinkID(linkid);
                    if (url_name != null) {
                        String clean = url_name.replaceAll("(watch_)?(free_)?(live_)?camgirls_at_(www(_|\\.))?teenhdcams(_|\\.)com$", "");
                        clean = clean.replaceAll("(watch_)?free_at_(www(_|\\.))?teenhdcams(_|\\.)com$", "");
                        clean = clean.replaceAll("(watch_)?full_video_at_(www(_|\\.))?teenhdcams(_|\\.)com$", "");
                        clean = clean.replaceAll("\\.*_*$", "");
                        name_temp = linkid + "_" + clean;
                    } else {
                        name_temp = linkid;
                    }
                    dl.setName(name_temp);
                    dl.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
                    /* Packagizer properties */
                    dl.setProperty("username", username);
                    decryptedLinks.add(dl);
                    distribute(dl);
                    decryptedLinksNum++;
                    dupeList.add(linkid);
                }
            }
            pageNum++;
        } while (!this.isAbort() && decryptedLinksNum >= 36);
        if (decryptedLinks.size() == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    private void crawlPhotos(final String parameter, final ArrayList<DownloadLink> decryptedLinks) throws IOException, PluginException {
        final Regex urlinfo = new Regex(parameter, "https?://[^/]+/([^/]+)/([^/]+)/([^/]+)/([^/]+)/([^/]+)");
        final String type = urlinfo.getMatch(0);
        final String username = urlinfo.getMatch(1);
        final String galleryID = urlinfo.getMatch(3);
        final String galleryName = urlinfo.getMatch(4);
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(username + " - " + galleryName);
        fp.addLinks(decryptedLinks);
        /* These are direct-URLs */
        final String[] links = br.getRegex("class=\"embed\\-responsive\\-item\" href=\"(https?[^\"]+)").getColumn(0);
        if (links == null || links.length == 0) {
            logger.info("Failed to find any photos");
            return;
        }
        int counter = 1;
        for (String singleLink : links) {
            if (this.isAbort()) {
                return;
            }
            String url_filename = getFileNameFromURL(new URL(singleLink));
            if (url_filename == null) {
                url_filename = "_" + counter;
            }
            url_filename = username + "_" + galleryID + "_" + galleryName + url_filename;
            final DownloadLink dl = createDownloadlink(singleLink);
            /* Usually we will crawl a lot of URLs at this stage --> Set onlinestatus right away! */
            dl.setAvailable(true);
            fp.add(dl);
            dl.setMimeHint(CompiledFiletypeFilter.ImageExtensions.JPG);
            dl.setFinalFileName(url_filename);
            /* Packagizer properties */
            dl.setProperty("username", username);
            decryptedLinks.add(dl);
            distribute(dl);
            counter++;
        }
        if (decryptedLinks.size() == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }
}
