(function () {
    const root = document.documentElement;
    const success = root.dataset.callbackSuccess === "true";
    const payload = {
        type: root.dataset.callbackType,
        success,
        code: root.dataset.callbackCode,
        message: root.dataset.callbackMessage,
    };

    if (window.opener && !window.opener.closed) {
        try {
            window.opener.postMessage(payload, window.location.origin);
        } catch (error) {
            console.error("Could not notify VinylMatch of the login result.", error);
        }
    }

    function returnToApp() {
        window.close();
        if (!window.closed) {
            window.location.replace(root.dataset.callbackTarget);
        }
    }

    if (success) {
        returnToApp();
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.getElementById("oauth-callback-action").addEventListener("click", (event) => {
            event.preventDefault();
            returnToApp();
        });
    });
}());
