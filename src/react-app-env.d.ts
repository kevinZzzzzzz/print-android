/// <reference types="react-scripts" />

declare namespace JSX {
  interface IntrinsicElements {
    option: React.DetailedHTMLProps<
      React.OptionHTMLAttributes<HTMLOptionElement>,
      HTMLOptionElement
    >;
  }
}
