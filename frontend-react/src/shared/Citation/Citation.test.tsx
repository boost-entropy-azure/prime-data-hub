import { screen } from "@testing-library/react";

import { Citation } from "./Citation";
import { renderApp } from "../../utils/CustomRenderUtils";

describe("Citation rendering", () => {
    const fakeCitation = {
        title: "Mock title",
        quote: "Mock quote",
        author: "Mock author",
        authorTitle: "Mock author title",
    };

    function setup() {
        renderApp(<Citation {...fakeCitation} />);
    }

    test("Citation renders props", () => {
        setup();
        const title = screen.getByTestId("title");
        const quote = screen.getByTestId("quote");
        const author = screen.getByTestId("author");
        const authorTitle = screen.getByTestId("authorTitle");

        expect(title).toBeInTheDocument();
        expect(quote).toBeInTheDocument();
        expect(author).toBeInTheDocument();
        expect(quote).toBeInTheDocument();
        expect(title.innerHTML).toEqual(fakeCitation.title);
        expect(quote.innerHTML).toEqual(`"${fakeCitation.quote}"`);
        expect(author.innerHTML).toEqual(fakeCitation.author);
        expect(authorTitle.innerHTML).toEqual(fakeCitation.authorTitle);
    });
});
