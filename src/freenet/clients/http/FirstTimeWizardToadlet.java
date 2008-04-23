/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/**
 * A first time wizard aimed to ease the configuration of the node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * 
 * TODO: a choose your CSS step ?
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final NodeClientCore core;
	private final Config config;
	
	
	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.core = core;
		this.config = node.config;
	}
	
	public static final String TOADLET_URL = "/wizard/";
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		int currentStep = request.getIntParam("step");
		
		if(currentStep == 1) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step1Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode opennetInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode opennetInfoboxHeader = opennetInfobox.addChild("div", "class", "infobox-header");
			HTMLNode opennetInfoboxContent = opennetInfobox.addChild("div", "class", "infobox-content");
			
			opennetInfoboxHeader.addChild("#", l10n("connectToStrangers"));
			opennetInfoboxContent.addChild("p", l10n("connectToStrangersLong"));
			opennetInfoboxContent.addChild("p", l10n("enableOpennet"));
			HTMLNode opennetForm = ctx.addFormChild(opennetInfoboxContent, ".", "opennetForm");
			HTMLNode opennetDiv = opennetForm.addChild("div", "class", "opennetDiv");
			opennetDiv.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "enableOpennet", "true" }, l10n("opennetYes"));
			opennetDiv.addChild("br");
			opennetDiv.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "enableOpennet", "false" }, l10n("opennetNo"));
			HTMLNode para = opennetForm.addChild("p");
			para.addChild("b", l10n("warningTitle")+' ');
			L10n.addL10nSubstitution(para, "FirstTimeWizardToadlet.opennetWarning", new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			opennetForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "opennetF", L10n.getString("FirstTimeWizardToadlet.continue")});
			opennetForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == 2) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step2Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
			HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");
			
			nnameInfoboxHeader.addChild("#", l10n("chooseNodeName"));
			nnameInfoboxContent.addChild("#", l10n("chooseNodeNameLong"));
			HTMLNode nnameForm = ctx.addFormChild(nnameInfoboxContent, ".", "nnameForm");
			nnameForm.addChild("input", "name", "nname");
			
			nnameForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "nnameF", L10n.getString("FirstTimeWizardToadlet.continue")});
			nnameForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == 3) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step3Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
			
			bandwidthnfoboxHeader.addChild("#", l10n("bandwidthLimit"));
			bandwidthInfoboxContent.addChild("#", l10n("bandwidthLimitLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "bwForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "bw");
			
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "15K", "selected" }, "I don't know");
			result.addChild("option", "value", "8K", "lower speed");
			result.addChild("option", "value", "12K", "512+/128 kbps");
			result.addChild("option", "value", "24K", "1024+/256 kbps");
			result.addChild("option", "value", "48K", "1024+/512 kbps");
			result.addChild("option", "value", "96K", "1024+/1024 kbps");
			result.addChild("option", "value", "1000K", "higher speed");
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "bwF", L10n.getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == 4) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step4Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
			
			bandwidthnfoboxHeader.addChild("#", l10n("datastoreSize"));
			bandwidthInfoboxContent.addChild("#", l10n("datastoreSizeLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "dsForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "ds");
			
			// Use JNI to find out the free space on this partition.

			long freeSpace = -1;
			File dir = FileUtil.getCanonicalFile(core.node.getNodeDir());
			try {
				Class c = dir.getClass();
				Method m = c.getDeclaredMethod("getFreeSpace", new Class[0]);
				if(m != null) {
					Long lFreeSpace = (Long) m.invoke(dir, new Object[0]);
					if(lFreeSpace != null) {
						freeSpace = lFreeSpace.longValue();
						System.err.println("Found free space on node's partition: "+freeSpace+" on "+dir+" = "+SizeUtil.formatSize(freeSpace));
					}
				}
			} catch (NoSuchMethodException e) {
				// Ignore
				freeSpace = -1;
			} catch (Throwable t) {
				System.err.println("Trying to access 1.6 getFreeSpace(), caught "+t);
				freeSpace = -1;
			}
			
			if(freeSpace <= 0) {
				result.addChild("option", new String[] { "value", "selected" }, new String[] { "1G", "selected" }, "1GiB");
			} else {
				if(freeSpace / 10 > 1024*1024*1024) {
					// If 10GB+ free, default to 10% of available disk space.
					String size = SizeUtil.formatSize(freeSpace/10);
					String shortSize = SizeUtil.stripBytesEtc(size);
					result.addChild("option", new String[] { "value", "selected" }, new String[] { shortSize, "selected" }, size+" "+l10n("tenPercentDisk"));
					if(freeSpace / 20 > 1024*1024*1024) {
						// If 20GB+ free, also offer 5% of available disk space.
						size = SizeUtil.formatSize(freeSpace/20);
						shortSize = SizeUtil.stripBytesEtc(size);
						result.addChild("option", "value", shortSize, size+" "+l10n("fivePercentDisk"));
					}
					result.addChild("option", "value", "1G", "1GiB");
				} else if(freeSpace < 1024*1024*1024) {
					// If less than 1GB free, default to 256MB and also offer 512MB.
					result.addChild("option", new String[] { "value", "selected" }, new String[] { "256M", "selected" }, "256MiB");
					result.addChild("option", "value", "512M", "512MiB");
				} else if(freeSpace < 5*1024*1024*1024) {
					// If less than 5GB free, default to 512MB
					result.addChild("option", new String[] { "value", "selected" }, new String[] { "512M", "selected" }, "512MiB");						
					result.addChild("option", "value", "1G", "1GiB");
				} else {
					// If unknown, or 5-10GB free, default to 1GB.
					result.addChild("option", new String[] { "value", "selected" }, new String[] { "1G", "selected" }, "1GiB");
				}
			}
			result.addChild("option", "value", "2G", "2GiB");
			result.addChild("option", "value", "3G", "3GiB");
			result.addChild("option", "value", "5G", "5GiB");
			result.addChild("option", "value", "10G", "10GiB");
			result.addChild("option", "value", "20G", "20GiB");
			result.addChild("option", "value", "30G", "30GiB");
			result.addChild("option", "value", "50G", "50GiB");
			result.addChild("option", "value", "100G", "100GiB");
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dsF", L10n.getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}else if(currentStep == 7) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step7Title"), true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode congratzInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode congratzInfoboxHeader = congratzInfobox.addChild("div", "class", "infobox-header");
			HTMLNode congratzInfoboxContent = congratzInfobox.addChild("div", "class", "infobox-content");

			congratzInfoboxHeader.addChild("#", l10n("congratz"));
			congratzInfoboxContent.addChild("p", l10n("congratzLong"));
			
			congratzInfoboxContent.addChild("a", "href", "/", L10n.getString("FirstTimeWizardToadlet.continueEnd"));

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("homepageTitle"), false, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", l10n("welcomeInfoboxTitle"));
		
		HTMLNode firstParagraph = welcomeInfoboxContent.addChild("p");
		firstParagraph.addChild("#", l10n("welcomeInfoboxContent1"));
		HTMLNode secondParagraph = welcomeInfoboxContent.addChild("p");
		secondParagraph.addChild("a", "href", "?step=1").addChild("#", L10n.getString("FirstTimeWizardToadlet.clickContinue"));
		
		HTMLNode thirdParagraph = welcomeInfoboxContent.addChild("p");
		thirdParagraph.addChild("a", "href", "/").addChild("#", l10n("skipWizard"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			super.writeTemporaryRedirect(ctx, "invalid/unhandled data", "/");
			return;
		}
		
		
		if(request.isPartSet("enableOpennet")) {
			String isOpennetEnabled = request.getPartAsString("enableOpennet", 255);
			boolean enable;
			try {
				enable = Fields.stringToBool(isOpennetEnabled);
			} catch (NumberFormatException e) {
				Logger.error(this, "Invalid opennetEnabled: "+isOpennetEnabled, e);
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step=1");
				return;
			}
			try {
				config.get("node.opennet").set("enabled", enable);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen setting opennet.enabled="+enable+" please repot: "+e, e);
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step=1");
				return;
			}
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step=2");
			return;
		} else if(request.isPartSet("nnameF")) {
			String selectedNName = request.getPartAsString("nname", 128);
			
			try {
				config.get("node").set("name", selectedNName);
				Logger.normal(this, "The node name has been set to "+ selectedNName);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step=3");
			return;
		} else if(request.isPartSet("bwF")) {
			String selectedUploadSpeed =request.getPartAsString("bw", 6);
			
			try {
				config.get("node").set("outputBandwidthLimit", selectedUploadSpeed);
				Logger.normal(this, "The outputBandwidthLimit has been set to "+ selectedUploadSpeed);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step=4");
			return;
		} else if(request.isPartSet("dsF")) {
			String selectedStoreSize =request.getPartAsString("ds", 6);
			
			try {
				config.get("node").set("storeSize", selectedStoreSize);
				Logger.normal(this, "The storeSize has been set to "+ selectedStoreSize);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			super.writeTemporaryRedirect(ctx, "step5", TOADLET_URL+"?step=7");
			return;
		}
		
		super.writeTemporaryRedirect(ctx, "invalid/unhandled data", TOADLET_URL);
	}
	
	private String l10n(String key) {
		return L10n.getString("FirstTimeWizardToadlet."+key);
	}

	public String supportedMethods() {
		return "GET, POST";
	}
}
