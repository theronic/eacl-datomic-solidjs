import { render } from "solid-js/web";
import { App } from "./App";
import { AppStateProvider } from "./state";
import "./styles.css";

const root = document.getElementById("root");
if (!root) throw new Error("Missing #root element");

render(
  () => (
    <AppStateProvider>
      <App />
    </AppStateProvider>
  ),
  root,
);
