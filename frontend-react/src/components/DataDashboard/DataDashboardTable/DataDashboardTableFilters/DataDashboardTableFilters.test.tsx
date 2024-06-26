import { screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import DataDashboardTableFilters, {
    isValidDateString,
    TableFilterDateLabel,
} from "./DataDashboardTableFilters";
import {
    cursorManagerFixture,
    filterManagerFixture,
} from "../../../../hooks/filters/filters.fixtures";
import { renderApp } from "../../../../utils/CustomRenderUtils";

describe("Rendering", () => {
    function setup() {
        renderApp(
            <DataDashboardTableFilters
                startDateLabel={TableFilterDateLabel.START_DATE}
                endDateLabel={TableFilterDateLabel.END_DATE}
                filterManager={filterManagerFixture}
                cursorManager={cursorManagerFixture}
            />,
        );
    }

    test("renders without error", async () => {
        setup();
        const container = await screen.findByTestId("filter-container");
        expect(container).toBeInTheDocument();
    });

    test("pickers render", async () => {
        setup();
        /* Trussworks USWDS library uses the placeholder text in two different
         *  HTML elements, so we have to use getAllBy...() rather than getBy...()
         *  and assert that they are non-null.
         * */
        const datePickers = await screen.findAllByTestId(
            "date-picker-internal-input",
        );
        expect(datePickers).toHaveLength(2);
    });
});

describe("when validating values", () => {
    const VALID_FROM = "01/01/2023";
    const VALID_TO = "02/02/2023";
    const INVALID_DATE = "99/99/9999";

    let startDateNode: HTMLElement;
    let endDateNode: HTMLElement;
    let filterButtonNode: HTMLElement;

    function setup() {
        renderApp(
            <DataDashboardTableFilters
                startDateLabel={TableFilterDateLabel.START_DATE}
                endDateLabel={TableFilterDateLabel.END_DATE}
                filterManager={filterManagerFixture}
                cursorManager={cursorManagerFixture}
            />,
        );

        [startDateNode, endDateNode] = screen.getAllByTestId(
            "date-picker-external-input",
        );
        filterButtonNode = screen.getByText("Filter");
    }

    describe("by default", () => {
        test("enables the Filter button with the fallback values", () => {
            setup();
            expect(filterButtonNode).toHaveProperty("disabled", false);
        });
    });

    describe("when both values are valid", () => {
        test("enables the Filter button", async () => {
            setup();

            await userEvent.type(startDateNode, VALID_FROM);
            await userEvent.type(endDateNode, VALID_TO);
            expect(filterButtonNode).toHaveProperty("disabled", true);
        });
    });

    describe("when both values are invalid", () => {
        test("disables the Filter button", async () => {
            setup();

            await userEvent.type(startDateNode, INVALID_DATE);
            await userEvent.type(endDateNode, INVALID_DATE);
            expect(filterButtonNode).toHaveProperty("disabled", true);
        });
    });

    describe("when the from range is invalid", () => {
        test("disables the Filter button", async () => {
            setup();

            await userEvent.type(startDateNode, INVALID_DATE);
            await userEvent.type(endDateNode, VALID_TO);
            expect(filterButtonNode).toHaveProperty("disabled", true);
        });
    });

    describe("when the to range is invalid", () => {
        test("disables the Filter button", async () => {
            setup();

            await userEvent.type(startDateNode, VALID_FROM);
            await userEvent.type(endDateNode, INVALID_DATE);
            expect(filterButtonNode).toHaveProperty("disabled", true);
        });
    });

    describe("when the from value is greater than the to value", () => {
        test("disables the Filter button", async () => {
            setup();
            await userEvent.type(startDateNode, VALID_TO);
            await userEvent.type(endDateNode, VALID_FROM);
            expect(filterButtonNode).toHaveProperty("disabled", true);
        });
    });
});

describe("isValidDateString", () => {
    test("returns true only when the date string is well-formed and a valid date", () => {
        expect(isValidDateString("1/1/23")).toEqual(true);
        expect(isValidDateString("1/1/2023")).toEqual(true);
        expect(isValidDateString("01/1/2023")).toEqual(true);
        expect(isValidDateString("01/01/2023")).toEqual(true);
        expect(isValidDateString("01/01/2023")).toEqual(true);

        expect(isValidDateString("")).toEqual(false);
        expect(isValidDateString("99/99")).toEqual(false);
        expect(isValidDateString("99/99/9999")).toEqual(false);
        expect(isValidDateString("01/99/2023")).toEqual(false);
        expect(isValidDateString("abc")).toEqual(false);
    });
});
