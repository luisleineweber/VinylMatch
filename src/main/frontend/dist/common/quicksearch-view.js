import { buildAllVendorLinks } from "./vendors.js";

export function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
}

function artwork(url, className = "qs-artwork") {
    const frame = element("span", className, "♪");
    if (url) {
        const image = element("img");
        image.src = url;
        image.alt = "";
        image.loading = "lazy";
        image.addEventListener("error", () => image.remove(), { once: true });
        frame.replaceChildren(image);
    }
    frame.setAttribute("aria-hidden", "true");
    return frame;
}

function externalLink(label, url, className = "qs-link") {
    const link = element("a", className, label);
    if (!/^https?:\/\//i.test(url || "")) return null;
    link.href = url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.setAttribute("aria-label", `${label.replace("↗", "").trim()}, opens in a new tab`);
    return link;
}

export function renderItems(list, items, select) {
    list.replaceChildren();
    for (const item of items) {
        const row = element("li");
        const button = element("button", "qs-result");
        button.type = "button";
        button.dataset.key = `${item.kind}:${item.id}`;
        button.title = [item.title, item.artist, item.songQuery ? `Song search: ${item.songQuery}` : null].filter(Boolean).join(" · ");
        const copy = element("span", "qs-result-copy");
        copy.append(element("span", "qs-result-title", item.title));
        const subtitle = item.kind === "artist" ? "View releases" :
            [item.artist, item.year, item.songQuery ? `Contains “${item.songQuery}”` : null].filter(Boolean).join(" · ");
        copy.append(element("span", "qs-result-subtitle", subtitle));
        const label = item.kind === "artist" ? "Artist" : item.songQuery ? "Song match" : "Album";
        button.append(artwork(item.image), copy, element("span", "qs-result-kind", label));
        button.addEventListener("click", () => select(item));
        row.append(button);
        list.append(row);
    }
}

export function renderLoading(container) {
    container.replaceChildren();
    const rows = element(container.tagName === "UL" ? "li" : "div", "qs-skeletons");
    rows.setAttribute("aria-hidden", "true");
    for (let i = 0; i < 5; i++) rows.append(element("div", "qs-skeleton"));
    container.append(rows);
}

export function renderMessage(container, title, message, retry) {
    const panel = element(container.tagName === "UL" ? "li" : "div", "qs-message");
    panel.append(element("h3", "", title), element("p", "", message));
    if (retry) {
        const button = element("button", "qs-button", "Try again");
        button.type = "button";
        button.addEventListener("click", retry);
        panel.append(button);
    }
    container.replaceChildren(panel);
}

export function renderArtist(container, artist, select, loadMore) {
    const heading = element("div", "qs-detail-heading");
    const copy = element("div");
    const title = element("h3", "", artist.name);
    title.tabIndex = -1;
    copy.append(title, element("p", "qs-description", "Albums, EPs and singles. Editions are grouped where Discogs provides a master release."));
    heading.append(artwork(artist.image, "qs-artwork qs-detail-artwork"), copy);
    container.replaceChildren(heading);
    const link = externalLink("View artist on Discogs ↗", artist.url);
    if (link) container.append(link);
    const albums = element("ul", "qs-artist-albums");
    renderItems(albums, artist.albums, select);
    container.append(element("h4", "qs-section-title", "Releases"), albums);
    if (!artist.albums.length) container.append(element("p", "qs-description", "No main releases on this page."));
    if (artist.page < artist.pages) {
        const more = element("button", "qs-button qs-load-more", "Load more releases");
        more.type = "button";
        more.addEventListener("click", loadMore);
        container.append(more);
    }
    return title;
}

export function renderAlbum(container, album, openArtist, songQuery) {
    const heading = element("div", "qs-detail-heading");
    const copy = element("div");
    const title = element("h3", "", album.title);
    title.tabIndex = -1;
    copy.append(title);
    const artists = element("div", "qs-album-artists");
    for (const artist of album.artists) {
        const button = element("button", "qs-text-button", artist.name);
        button.type = "button";
        button.dataset.key = `artist:${artist.id}`;
        button.addEventListener("click", () => openArtist({ id: artist.id, kind: "artist", title: artist.name }));
        artists.append(button);
    }
    if (album.year) artists.append(element("span", "qs-description", String(album.year)));
    copy.append(artists);
    heading.append(artwork(album.image, "qs-artwork qs-detail-artwork"), copy);
    container.replaceChildren(heading);
    const link = externalLink("View release on Discogs ↗", album.url);
    if (link) container.append(link);
    container.append(element("h4", "qs-section-title", "Find this record"));
    const providers = element("ul", "qs-providers");
    renderProviders(providers, album);
    container.append(providers, element("p", "qs-description qs-provider-note", "Discogs counts include all formats. Shop links open a search. Check the edition and stock with the seller."));
    if (album.vinyl === false) container.append(element("p", "qs-description", "This edition is not vinyl. Check Discogs for a vinyl edition."));
    if (album.tracks.length) {
        container.append(element("h4", "qs-section-title", "Track list"));
        const tracks = element("ol", "qs-tracks");
        for (const track of album.tracks) {
            const row = element("li");
            if (songQuery && track.title.toLocaleLowerCase().includes(songQuery.toLocaleLowerCase())) row.classList.add("qs-matched-track");
            row.append(element("span", "qs-track-position", track.position), element("span", "", track.title),
                element("span", "qs-description", track.duration));
            tracks.append(row);
        }
        container.append(tracks);
    }
    return title;
}

export function renderProviders(providers, album) {
    const discogs = element("li", "qs-provider");
    const detail = element("div");
    detail.append(element("strong", "", "Discogs"));
    let status = "Offer count unavailable";
    if (album.offers === 0) status = "No current offers";
    if (album.offers > 0) status = `${album.offers} offers · all formats`;
    detail.append(element("p", "qs-description", status));
    discogs.append(detail);
    const marketplace = externalLink("Check vinyl offers ↗", album.marketplaceUrl, "qs-button");
    if (marketplace) {
        marketplace.dataset.key = "marketplace";
        discogs.append(marketplace);
    }
    providers.replaceChildren(discogs);
    for (const { vendor, url } of buildAllVendorLinks({ artist: album.artist, album: album.title, releaseYear: album.year })) {
        const link = externalLink("Search shop ↗", url, "qs-button");
        if (!link) continue;
        link.dataset.key = `shop:${vendor.id}`;
        link.setAttribute("aria-label", `Search shop: ${vendor.name}, ${album.title}, opens in a new tab`);
        const row = element("li", "qs-provider");
        const detail = element("div");
        detail.append(element("strong", "", vendor.name), element("p", "qs-description", "Stock not checked"));
        row.append(detail, link);
        providers.append(row);
    }
}
