/**
 * Vendor registry for vinyl store search links.
 * 
 * Allows centralized management of vendor search templates and
 * optional custom vendor configuration via backend.
 */

// Default built-in vendors
const DEFAULT_VENDORS = [
    {
        id: "hhv",
        label: "H",
        name: "HHV",
        iconLight: "/design/hhv_trans_black.svg",
        iconDark: "/design/hhv_trans_white.svg",
        urlTemplate: "https://www.hhv.de/en/catalog/filter/search-S11?af=true&term={query}",
        enabled: true,
    },
    {
        id: "jpc",
        label: "J",
        name: "JPC",
        iconLight: "/design/jpc_trans_black.svg",
        iconDark: "/design/jpc_trans_white.svg",
        urlTemplate: "https://www.jpc.de/s/{query}",
        enabled: true,
    },
    {
        id: "amazon",
        label: "A",
        name: "Amazon",
        iconLight: "/design/amazon_trans_black.svg",
        iconDark: "/design/amazon_trans_white.svg",
        urlTemplate: "https://www.amazon.de/s?k={query}+vinyl",
        enabled: true,
    },
];

// Runtime vendor list (starts with defaults, can be extended)
let vendors = [...DEFAULT_VENDORS];

/**
 * Gets the current list of enabled vendors.
 */
export function getVendors() {
    return vendors.filter(v => v.enabled !== false);
}

/**
 * Gets all vendors including disabled ones.
 */
export function getAllVendors() {
    return [...vendors];
}

/**
 * Adds a custom vendor to the registry.
 */
export function addVendor(vendor) {
    if (!vendor || !vendor.id || !vendor.urlTemplate) {
        console.warn("Invalid vendor config:", vendor);
        return false;
    }
    const iconLight = typeof vendor.iconLight === "string"
        ? vendor.iconLight
        : (typeof vendor?.icon?.light === "string" ? vendor.icon.light : null);
    const iconDark = typeof vendor.iconDark === "string"
        ? vendor.iconDark
        : (typeof vendor?.icon?.dark === "string" ? vendor.icon.dark : null);
    // Remove existing vendor with same ID
    vendors = vendors.filter(v => v.id !== vendor.id);
    vendors.push({
        id: vendor.id,
        label: vendor.label || vendor.id.charAt(0).toUpperCase(),
        name: vendor.name || vendor.id,
        iconLight,
        iconDark,
        urlTemplate: vendor.urlTemplate,
        enabled: vendor.enabled !== false,
    });
    return true;
}

/**
 * Sets multiple vendors, replacing existing ones with matching IDs.
 */
export function setVendors(newVendors) {
    if (!Array.isArray(newVendors)) return;
    for (const vendor of newVendors) {
        addVendor(vendor);
    }
}

/**
 * Resets vendors to defaults only.
 */
export function resetVendors() {
    vendors = [...DEFAULT_VENDORS];
}

/**
 * Enables or disables a vendor by ID.
 */
export function setVendorEnabled(vendorId, enabled) {
    const vendor = vendors.find(v => v.id === vendorId);
    if (vendor) {
        vendor.enabled = enabled;
    }
}

/**
 * Builds a search URL for a vendor.
 * 
 * @param {object} vendor - The vendor config
 * @param {object} track - Track data with artist, album, releaseYear
 * @returns {string|null} - The search URL or null if not possible
 */
export function buildVendorUrl(vendor, track) {
    if (!vendor || !vendor.urlTemplate) return null;

    const artist = normalizeForSearch(primaryArtist(track?.artist));
    const album = normalizeForSearch(track?.album);
    
    const queryParts = [artist, album].filter(Boolean);
    if (track?.releaseYear) {
        queryParts.push(String(track.releaseYear));
    }
    
    const query = queryParts.join(" ").trim();
    if (!query) return null;

    let encoded = encodeURIComponent(query);
    if (vendor.id === "hhv") {
        // HHV expects querystring-style space encoding ("+") for the `term` parameter.
        encoded = encoded.replace(/%20/g, "+");
    }
    return vendor.urlTemplate.replace("{query}", encoded);
}

/**
 * Builds search links for all enabled vendors.
 * 
 * @param {object} track - Track data
 * @returns {Array<{vendor: object, url: string|null}>}
 */
export function buildAllVendorLinks(track) {
    return getVendors().map(vendor => ({
        vendor,
        url: buildVendorUrl(vendor, track),
    }));
}

/**
 * Loads custom vendor configuration from the backend.
 * Returns whether custom settings were loaded.
 */
export async function loadCustomVendors() {
    try {
        const response = await fetch("/api/config/vendors", { 
            cache: "no-cache",
            credentials: "include"
        });
        if (!response.ok) {
            console.warn("[Vendors] Could not load custom settings:", response.status);
            return false;
        }
        
        const data = await response.json();
        if (!Array.isArray(data?.vendors)) {
            console.error("[Vendors] Invalid custom settings response");
            return false;
        }
        setVendors(data.vendors);
        return true;
    } catch (e) {
        console.error("[Vendors] Could not load custom settings", e);
        return false;
    }
}

// =========================================================================
// String normalization helpers (duplicated to avoid circular imports)
// =========================================================================

function normalizeForSearch(value) {
    if (!value) return "";
    return removeMarketingSuffix(stripBracketedContent(value.replace(/&/g, "and")));
}

function primaryArtist(artist) {
    if (!artist) return "";
    const tokens = artist.split(/\s*(?:,|;|\/|&|\+|\band\b|\s+(?:feat\.?|featuring|ft\.?|with|x)\s+)\s*/i);
    const candidate = tokens[0]?.trim();
    return (candidate && candidate.length ? candidate : artist).trim();
}

function stripBracketedContent(value) {
    if (!value) return "";
    return value
        .replace(/\s*\([^)]*\)/g, "")
        .replace(/\s*\[[^\]]*\]/g, "")
        .replace(/\s*\{[^}]*\}/g, "")
        .trim();
}

function removeMarketingSuffix(value) {
    if (!value) return "";
    return value.replace(/\s*-\s*(remaster(ed)?|deluxe|expanded|anniversary|edition|remix|reissue)\b.*$/i, "").trim();
}
