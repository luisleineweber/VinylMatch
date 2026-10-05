(function () {
    const card = document.querySelector("[data-callback-message]");
    if (!card) return;

    const success = card.dataset.callbackSuccess === "true";
    const payload = { type: "discogs-auth-callback", success };
    if (!success && card.dataset.callbackMessage) {
        payload.message = card.dataset.callbackMessage;
    }

    if (window.opener && !window.opener.closed) {
        window.opener.postMessage(payload, window.location.origin);
    }

    if (success) {
        setTimeout(() => {
            window.close();
            if (!window.closed) {
                window.location.href = "/playlist.html";
            }
        }, 600);
    }
}());
