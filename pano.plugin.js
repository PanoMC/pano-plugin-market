// Plugin-level options of the Pano plugin kit (@panomc/plugin-kit). The namespace is `market`
// (the plugin id minus `pano-plugin-`); the views are the files of these two folders, so none moved.
export default {
  viewDirs: ['src/theme/pages', 'src/theme/components'],
  styles: {
    // Views that may set more than custom properties in `style=`: the admin-chosen category colour, the payment method
    // brand colour and the goal progress width. Everything else keeps its look in classes a theme can restyle.
    styleAttrAllow: ['CategoryNode', 'PaymentMethodPicker', 'GoalWidget'],
  },
};
