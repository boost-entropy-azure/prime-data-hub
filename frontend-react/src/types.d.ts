import { type RouteObject } from "react-router";
import { createBrowserRouter } from "react-router-dom";

declare global {
    type RsRouteObject = Omit<RouteObject, "children" | "handle"> & {
        children?: RsRouteObject[];
        handle?: {
            /**
             * Used to flag a route as a content (CMS) focused page in order to
             * make style or layout changes specific to this area of the
             * application. Default is assumed false, aka the route is
             * for an app (functional) focused page.
             */
            isContentPage?: boolean;
            isFullWidth?: boolean;
            isLoginPage?: boolean;
        };
    };

    type RemixRouter = ReturnType<typeof createBrowserRouter>;
}
